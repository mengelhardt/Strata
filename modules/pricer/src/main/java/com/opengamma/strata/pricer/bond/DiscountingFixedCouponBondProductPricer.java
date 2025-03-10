/*
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.bond;

import static com.opengamma.strata.math.MathUtils.pow2;
import static com.opengamma.strata.product.bond.FixedCouponBondYieldConvention.DE_BONDS;
import static com.opengamma.strata.product.bond.FixedCouponBondYieldConvention.GB_BUMP_DMO;
import static com.opengamma.strata.product.bond.FixedCouponBondYieldConvention.JP_SIMPLE;
import static com.opengamma.strata.product.bond.FixedCouponBondYieldConvention.US_STREET;

import java.time.LocalDate;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.CurrencyAmount;
import com.opengamma.strata.basics.currency.Payment;
import com.opengamma.strata.basics.value.ValueDerivatives;
import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.market.sensitivity.PointSensitivityBuilder;
import com.opengamma.strata.math.impl.rootfinding.BracketRoot;
import com.opengamma.strata.math.impl.rootfinding.BrentSingleRootFinder;
import com.opengamma.strata.math.impl.rootfinding.NewtonRaphsonSingleRootFinder;
import com.opengamma.strata.math.impl.rootfinding.RealSingleRootFinder;
import com.opengamma.strata.pricer.CompoundedRateType;
import com.opengamma.strata.pricer.DiscountingPaymentPricer;
import com.opengamma.strata.pricer.ZeroRateSensitivity;
import com.opengamma.strata.product.Security;
import com.opengamma.strata.product.bond.FixedCouponBondPaymentPeriod;
import com.opengamma.strata.product.bond.FixedCouponBondYieldConvention;
import com.opengamma.strata.product.bond.ResolvedFixedCouponBond;

/**
 * Pricer for fixed coupon bond products.
 * <p>
 * This function provides the ability to price a {@link ResolvedFixedCouponBond}.
 * 
 * <h4>Price</h4>
 * Strata uses <i>decimal prices</i> for bonds in the trade model, pricers and market data.
 * For example, a price of 99.32% is represented in Strata by 0.9932.
 */
public class DiscountingFixedCouponBondProductPricer {

  /**
   * Default implementation.
   */
  public static final DiscountingFixedCouponBondProductPricer DEFAULT = new DiscountingFixedCouponBondProductPricer(
      DiscountingFixedCouponBondPaymentPeriodPricer.DEFAULT,
      DiscountingPaymentPricer.DEFAULT);

  /**
   * Brackets a root.
   */
  private static final BracketRoot ROOT_BRACKETER = new BracketRoot();

  /**
   * Pricer for {@link Payment}.
   */
  private final DiscountingPaymentPricer nominalPricer;
  /**
   * Pricer for {@link FixedCouponBondPaymentPeriod}.
   */
  private final DiscountingFixedCouponBondPaymentPeriodPricer periodPricer;

  /**
   * The root finder.
   */
  private final RealSingleRootFinder rootFinder;

  /**
   * Creates an instance.
   * 
   * @param periodPricer  the pricer for {@link FixedCouponBondPaymentPeriod}
   * @param nominalPricer  the pricer for {@link Payment}
   */
  public DiscountingFixedCouponBondProductPricer(
      DiscountingFixedCouponBondPaymentPeriodPricer periodPricer,
      DiscountingPaymentPricer nominalPricer) {

    this(periodPricer, nominalPricer, new BrentSingleRootFinder());
  }

  /**
   * Creates an instance. If the {@link NewtonRaphsonSingleRootFinder} is used,
   * then the coupon rate will be used as the starting point when finding yield.
   * 
   * @param periodPricer  the pricer for {@link FixedCouponBondPaymentPeriod}
   * @param nominalPricer  the pricer for {@link Payment}
   * @param rootFinder  the root finder
   */
  public DiscountingFixedCouponBondProductPricer(
      DiscountingFixedCouponBondPaymentPeriodPricer periodPricer,
      DiscountingPaymentPricer nominalPricer,
      RealSingleRootFinder rootFinder) {

    this.nominalPricer = ArgChecker.notNull(nominalPricer, "nominalPricer");
    this.periodPricer = ArgChecker.notNull(periodPricer, "periodPricer");
    this.rootFinder = ArgChecker.notNull(rootFinder, "rootFinder");
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the present value of the fixed coupon bond product.
   * <p>
   * The present value of the product is the value on the valuation date.
   * The result is expressed using the payment currency of the bond.
   * <p>
   * Coupon payments of the product are considered based on the valuation date.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @return the present value of the fixed coupon bond product
   */
  public CurrencyAmount presentValue(ResolvedFixedCouponBond bond, LegalEntityDiscountingProvider provider) {
    return presentValue(bond, provider, provider.getValuationDate());
  }

  // calculate the present value
  CurrencyAmount presentValue(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      LocalDate referenceDate) {

    IssuerCurveDiscountFactors issuerDf = issuerCurveDf(bond, provider);
    CurrencyAmount pvNominal = nominalPricer.presentValue(bond.getNominalPayment(), issuerDf.getDiscountFactors());
    CurrencyAmount pvCoupon = presentValueCoupon(bond, issuerDf, referenceDate);
    return pvNominal.plus(pvCoupon);
  }

  /**
   * Calculates the present value of the fixed coupon bond product with z-spread.
   * <p>
   * The present value of the product is the value on the valuation date.
   * The result is expressed using the payment currency of the bond.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or
   * periodic compounded rates of the issuer discounting curve.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param zSpread  the z-spread
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @return the present value of the fixed coupon bond product
   */
  public CurrencyAmount presentValueWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    return presentValueWithZSpread(
        bond, provider, zSpread, compoundedRateType, periodsPerYear, provider.getValuationDate());
  }

  // calculate the present value
  CurrencyAmount presentValueWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate referenceDate) {

    IssuerCurveDiscountFactors issuerDf = issuerCurveDf(bond, provider);
    CurrencyAmount pvNominal = nominalPricer.presentValueWithSpread(
        bond.getNominalPayment(), issuerDf.getDiscountFactors(), zSpread, compoundedRateType, periodsPerYear);
    CurrencyAmount pvCoupon = presentValueCouponFromZSpread(
        bond, issuerDf, zSpread, compoundedRateType, periodsPerYear, referenceDate);
    return pvNominal.plus(pvCoupon);
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the dirty price of the fixed coupon bond.
   * <p>
   * The fixed coupon bond is represented as {@link Security} where standard ID of the bond is stored.
   * <p>
   * Strata uses <i>decimal prices</i> for bonds. For example, a price of 99.32% is represented in Strata by 0.9932.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param refData  the reference data used to calculate the settlement date
   * @return the dirty price of the fixed coupon bond security
   */
  public double dirtyPriceFromCurves(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      ReferenceData refData) {

    LocalDate settlementDate = bond.getSettlementDateOffset().adjust(provider.getValuationDate(), refData);
    return dirtyPriceFromCurves(bond, provider, settlementDate);
  }

  /**
   * Calculates the dirty price of the fixed coupon bond under the specified settlement date.
   * <p>
   * The fixed coupon bond is represented as {@link Security} where standard ID of the bond is stored.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param settlementDate  the settlement date
   * @return the dirty price of the fixed coupon bond security
   */
  public double dirtyPriceFromCurves(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      LocalDate settlementDate) {

    CurrencyAmount pv = presentValue(bond, provider, settlementDate);
    RepoCurveDiscountFactors repoDf = repoCurveDf(bond, provider);
    double df = repoDf.discountFactor(settlementDate);
    double notional = bond.getNotional();
    return pv.getAmount() / df / notional;
  }

  /**
   * Calculates the dirty price of the fixed coupon bond with z-spread.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or periodic
   * compounded rates of the discounting curve.
   * <p>
   * The fixed coupon bond is represented as {@link Security} where standard ID of the bond is stored.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param refData  the reference data used to calculate the settlement date
   * @param zSpread  the z-spread
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @return the dirty price of the fixed coupon bond security
   */
  public double dirtyPriceFromCurvesWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      ReferenceData refData,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    LocalDate settlementDate = bond.getSettlementDateOffset().adjust(provider.getValuationDate(), refData);
    return dirtyPriceFromCurvesWithZSpread(bond, provider, zSpread, compoundedRateType, periodsPerYear, settlementDate);
  }

  /**
   * Calculates the dirty price of the fixed coupon bond under the specified settlement date with z-spread.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or periodic
   * compounded rates of the discounting curve.
   * <p>
   * The fixed coupon bond is represented as {@link Security} where standard ID of the bond is stored.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param zSpread  the z-spread
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @param settlementDate  the settlement date
   * @return the dirty price of the fixed coupon bond security
   */
  public double dirtyPriceFromCurvesWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate settlementDate) {

    CurrencyAmount pv = presentValueWithZSpread(bond, provider, zSpread, compoundedRateType, periodsPerYear, settlementDate);
    RepoCurveDiscountFactors repoDf = repoCurveDf(bond, provider);
    double df = repoDf.discountFactor(settlementDate);
    double notional = bond.getNotional();
    return pv.getAmount() / df / notional;
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the dirty price of the fixed coupon bond from its settlement date and clean price.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param cleanPrice  the clean price
   * @return the present value of the fixed coupon bond product
   */
  public double dirtyPriceFromCleanPrice(ResolvedFixedCouponBond bond, LocalDate settlementDate, double cleanPrice) {
    double notional = bond.getNotional();
    double accruedInterest = accruedInterest(bond, settlementDate);
    return cleanPrice + accruedInterest / notional;
  }

  /**
   * Calculates the clean price of the fixed coupon bond from its settlement date and dirty price.
   * <p>
   * Strata uses <i>decimal prices</i> for bonds. For example, a price of 99.32% is represented in Strata by 0.9932.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param dirtyPrice  the dirty price
   * @return the present value of the fixed coupon bond product
   */
  public double cleanPriceFromDirtyPrice(ResolvedFixedCouponBond bond, LocalDate settlementDate, double dirtyPrice) {
    double notional = bond.getNotional();
    double accruedInterest = accruedInterest(bond, settlementDate);
    return dirtyPrice - accruedInterest / notional;
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the z-spread of the fixed coupon bond from curves and dirty price.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or periodic
   * compounded rates of the discounting curve associated to the bond (Issuer Entity)
   * to match the dirty price.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param refData  the reference data used to calculate the settlement date
   * @param dirtyPrice  the dirtyPrice
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @return the z-spread of the fixed coupon bond security
   */
  public double zSpreadFromCurvesAndDirtyPrice(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      ReferenceData refData,
      double dirtyPrice,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    final Function<Double, Double> residual = new Function<Double, Double>() {
      @Override
      public Double apply(final Double z) {
        return dirtyPriceFromCurvesWithZSpread(
            bond, provider, refData, z, compoundedRateType, periodsPerYear) - dirtyPrice;
      }
    };
    double[] range = ROOT_BRACKETER.getBracketedPoints(residual, -0.01, 0.01); // Starting range is [-1%, 1%]
    return rootFinder.getRoot(residual, range[0], range[1]);
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the present value sensitivity of the fixed coupon bond product.
   * <p>
   * The present value sensitivity of the product is the sensitivity of the present value to
   * the underlying curves.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @return the present value curve sensitivity of the product
   */
  public PointSensitivityBuilder presentValueSensitivity(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider) {

    return presentValueSensitivity(bond, provider, provider.getValuationDate());
  }

  // calculate the present value sensitivity
  PointSensitivityBuilder presentValueSensitivity(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      LocalDate referenceDate) {

    IssuerCurveDiscountFactors issuerDf = issuerCurveDf(bond, provider);
    PointSensitivityBuilder pvNominal = presentValueSensitivityNominal(bond, issuerDf);
    PointSensitivityBuilder pvCoupon = presentValueSensitivityCoupon(bond, issuerDf, referenceDate);
    return pvNominal.combinedWith(pvCoupon);
  }

  /**
   * Calculates the present value sensitivity of the fixed coupon bond with z-spread.
   * <p>
   * The present value sensitivity of the product is the sensitivity of the present value to
   * the underlying curves.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or
   * periodic compounded rates of the issuer discounting curve.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param zSpread  the z-spread
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @return the present value curve sensitivity of the product
   */
  public PointSensitivityBuilder presentValueSensitivityWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    return presentValueSensitivityWithZSpread(
        bond, provider, zSpread, compoundedRateType, periodsPerYear, provider.getValuationDate());
  }

  // calculate the present value sensitivity
  PointSensitivityBuilder presentValueSensitivityWithZSpread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate referenceDate) {

    IssuerCurveDiscountFactors issuerDf = issuerCurveDf(bond, provider);
    PointSensitivityBuilder pvNominal = presentValueSensitivityNominalFromZSpread(
        bond, issuerDf, zSpread, compoundedRateType, periodsPerYear);
    PointSensitivityBuilder pvCoupon = presentValueSensitivityCouponFromZSpread(
        bond, issuerDf, zSpread, compoundedRateType, periodsPerYear, referenceDate);
    return pvNominal.combinedWith(pvCoupon);
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the dirty price sensitivity of the fixed coupon bond product.
   * <p>
   * The dirty price sensitivity of the security is the sensitivity of the present value to
   * the underlying curves.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param refData  the reference data used to calculate the settlement date
   * @return the dirty price value curve sensitivity of the security
   */
  public PointSensitivityBuilder dirtyPriceSensitivity(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      ReferenceData refData) {

    LocalDate settlementDate = bond.getSettlementDateOffset().adjust(provider.getValuationDate(), refData);
    return dirtyPriceSensitivity(bond, provider, settlementDate);
  }

  // calculate the dirty price sensitivity
  PointSensitivityBuilder dirtyPriceSensitivity(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      LocalDate settlementDate) {

    double notional = bond.getNotional();
    CurrencyAmount pv = presentValue(bond, provider, settlementDate);
    RepoCurveDiscountFactors repoDf = repoCurveDf(bond, provider);
    double df = repoDf.discountFactor(settlementDate);
    // Backward sweep
    double priceBar = 1.0;
    double pvBar = 1.0d / df / notional * priceBar;
    double dfBar = -pv.getAmount() / (df * df) / notional * priceBar;
    RepoCurveZeroRateSensitivity dfDr = repoDf.zeroRatePointSensitivity(settlementDate);
    PointSensitivityBuilder pvDr = presentValueSensitivity(bond, provider, settlementDate);
    return pvDr.multipliedBy(pvBar).combinedWith(dfDr.multipliedBy(dfBar));
  }

  /**
   * Calculates the dirty price sensitivity of the fixed coupon bond with z-spread.
   * <p>
   * The dirty price sensitivity of the security is the sensitivity of the present value to
   * the underlying curves.
   * <p>
   * The z-spread is a parallel shift applied to continuously compounded rates or periodic
   * compounded rates of the discounting curve.
   * 
   * @param bond  the product
   * @param provider  the discounting provider
   * @param refData  the reference data used to calculate the settlement date
   * @param zSpread  the z-spread
   * @param compoundedRateType  the compounded rate type
   * @param periodsPerYear  the number of periods per year
   * @return the dirty price curve sensitivity of the security
   */
  public PointSensitivityBuilder dirtyPriceSensitivityWithZspread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      ReferenceData refData,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    LocalDate settlementDate = bond.getSettlementDateOffset().adjust(provider.getValuationDate(), refData);
    return dirtyPriceSensitivityWithZspread(bond, provider, zSpread, compoundedRateType, periodsPerYear, settlementDate);
  }

  // calculate the dirty price sensitivity
  PointSensitivityBuilder dirtyPriceSensitivityWithZspread(
      ResolvedFixedCouponBond bond,
      LegalEntityDiscountingProvider provider,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate referenceDate) {

    RepoCurveDiscountFactors repoDf = repoCurveDf(bond, provider);
    double df = repoDf.discountFactor(referenceDate);
    CurrencyAmount pv = presentValueWithZSpread(bond, provider, zSpread, compoundedRateType, periodsPerYear);
    double notional = bond.getNotional();
    PointSensitivityBuilder pvSensi = presentValueSensitivityWithZSpread(
        bond, provider, zSpread, compoundedRateType, periodsPerYear).multipliedBy(1d / df / notional);
    RepoCurveZeroRateSensitivity dfSensi = repoDf.zeroRatePointSensitivity(referenceDate)
        .multipliedBy(-pv.getAmount() / df / df / notional);
    return pvSensi.combinedWith(dfSensi);
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the accrued interest of the fixed coupon bond with the specified settlement date.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @return the accrued interest of the product 
   */
  public double accruedInterest(ResolvedFixedCouponBond bond, LocalDate settlementDate) {
    double notional = bond.getNotional();
    return accruedYearFraction(bond, settlementDate) * bond.getFixedRate() * notional;
  }

  /**
   * Calculates the accrued year fraction of the fixed coupon bond with the specified settlement date.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @return the accrued year fraction of the product 
   */
  public double accruedYearFraction(ResolvedFixedCouponBond bond, LocalDate settlementDate) {
    if (bond.getUnadjustedStartDate().isAfter(settlementDate)) {
      return 0d;
    }
    FixedCouponBondPaymentPeriod period = bond.findPeriod(settlementDate)
        .orElseThrow(() -> new IllegalArgumentException("Date outside range of bond"));
    LocalDate previousAccrualDate = period.getUnadjustedStartDate();
    double accruedYearFraction = bond.yearFraction(previousAccrualDate, settlementDate);
    double result = 0d;
    if (settlementDate.isAfter(period.getDetachmentDate())) {
      result = accruedYearFraction - period.getYearFraction();
    } else {
      result = accruedYearFraction;
    }
    return result;
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the dirty price of the fixed coupon bond from yield.
   * <p>
   * The yield must be fractional.
   * The dirty price is computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the dirty price of the product 
   */
  public double dirtyPriceFromYield(ResolvedFixedCouponBond bond, LocalDate settlementDate, double yield) {
    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if (nCoupon == 1) {
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        FixedCouponBondPaymentPeriod payment = payments.get(payments.size() - 1);
        return (bond.getRedemptionRatio() + payment.getFixedRate() * payment.getYearFraction()) /
            (1d + factorToNextCoupon(bond, settlementDate) * yield /
                ((double) bond.getFrequency().eventsPerYear()));
      }
    }
    if ((yieldConv.equals(US_STREET)) || (yieldConv.equals(GB_BUMP_DMO)) || (yieldConv.equals(DE_BONDS))) {
      return dirtyPriceFromYieldStandard(bond, settlementDate, yield);
    }
    if (yieldConv.equals(JP_SIMPLE)) {
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      if (settlementDate.isAfter(maturityDate)) {
        return 0d;
      }
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double cleanPrice = (bond.getRedemptionRatio() + bond.getFixedRate() * maturity) / (1d + yield * maturity);
      return dirtyPriceFromCleanPrice(bond, settlementDate, cleanPrice);
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  /**
   * Calculates the dirty price of the fixed coupon bond from yield and its derivative wrt to the yield.
   * <p>
   * The yield must be fractional.
   * The dirty price is computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the dirty price of the product and its derivative
   */
  public ValueDerivatives dirtyPriceFromYieldAd(ResolvedFixedCouponBond bond, LocalDate settlementDate, double yield) {
    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if (nCoupon == 1) {
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        FixedCouponBondPaymentPeriod payment = payments.get(payments.size() - 1);
        double df = (1d + factorToNextCoupon(bond, settlementDate) * yield /
            ((double) bond.getFrequency().eventsPerYear()));
        double price = (bond.getRedemptionRatio() + payment.getFixedRate() * payment.getYearFraction()) / df;
        double yieldBar = -(bond.getRedemptionRatio() + payment.getFixedRate() * payment.getYearFraction()) /
            (df * df) * factorToNextCoupon(bond, settlementDate) / ((double) bond.getFrequency().eventsPerYear());
        return ValueDerivatives.of(price, DoubleArray.of(yieldBar));
      }
    }
    if ((yieldConv.equals(US_STREET)) || (yieldConv.equals(GB_BUMP_DMO)) || (yieldConv.equals(DE_BONDS))) {
      return dirtyPriceFromYieldStandardAd(bond, settlementDate, yield);
    }
    if (yieldConv.equals(JP_SIMPLE)) {
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      if (settlementDate.isAfter(maturityDate)) {
        double price = 0d;
        return ValueDerivatives.of(price, DoubleArray.of(0.0d));
      }
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double cleanPrice = (bond.getRedemptionRatio() + bond.getFixedRate() * maturity) / (1d + yield * maturity);
      double price = dirtyPriceFromCleanPrice(bond, settlementDate, cleanPrice);
      double yieldBar = -(bond.getRedemptionRatio() + bond.getFixedRate() * maturity) / ((1d + yield * maturity) * (1d + yield * maturity)) * maturity;
      return ValueDerivatives.of(price, DoubleArray.of(yieldBar));
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  // Computes the dirty price from the yield in the standard conventions
  private double dirtyPriceFromYieldStandard(
      ResolvedFixedCouponBond bond,
      LocalDate settlementDate,
      double yield) {

    int nbCoupon = bond.getPeriodicPayments().size() - 1;
    double factorOnPeriod = 1 + yield / ((double) bond.getFrequency().eventsPerYear());
    double fixedRate = bond.getFixedRate();
    double pvAtFirstCoupon = 0;
    int pow = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        pvAtFirstCoupon += fixedRate * period.getYearFraction() / Math.pow(factorOnPeriod, pow);
        ++pow;
      }
    }
    FixedCouponBondPaymentPeriod lastPeriod = bond.getPeriodicPayments().get(nbCoupon);
    double lastPow = pow - 1 + lastPeriod.getYearFraction() * bond.getFrequency().eventsPerYear();
    pvAtFirstCoupon += (bond.getRedemptionRatio() + fixedRate * lastPeriod.getYearFraction()) / Math.pow(factorOnPeriod, lastPow);
    return pvAtFirstCoupon * Math.pow(factorOnPeriod, -factorToNextCoupon(bond, settlementDate));
  }

  // Computes the dirty price from the yield in the standard conventions and its derivative wrt to the yield
  private ValueDerivatives dirtyPriceFromYieldStandardAd(
      ResolvedFixedCouponBond bond,
      LocalDate settlementDate,
      double yield) {

    int nbCoupon = bond.getPeriodicPayments().size() - 1;
    double factorOnPeriod = 1 + yield / ((double) bond.getFrequency().eventsPerYear());
    double fixedRate = bond.getFixedRate();
    double pvAtFirstCoupon = 0;
    int pow = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        pvAtFirstCoupon += fixedRate * period.getYearFraction() * Math.pow(factorOnPeriod, -pow);
        ++pow;
      }
    }
    FixedCouponBondPaymentPeriod lastPeriod = bond.getPeriodicPayments().get(nbCoupon);
    double lastPow = pow - 1 + lastPeriod.getYearFraction() * bond.getFrequency().eventsPerYear();
    pvAtFirstCoupon += (bond.getRedemptionRatio() + fixedRate * lastPeriod.getYearFraction()) * Math.pow(factorOnPeriod, -lastPow);
    double factorNextCoupon = factorToNextCoupon(bond, settlementDate);
    double priceAfter = Math.pow(factorOnPeriod, -factorNextCoupon);
    double price = pvAtFirstCoupon * priceAfter;
    // Backward sweep
    double factorOnPeriodBar = -lastPow * Math.pow(factorOnPeriod, -lastPow - 1) * priceAfter;
    int pow2 = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        factorOnPeriodBar +=
            fixedRate * period.getYearFraction() * -pow2 * Math.pow(factorOnPeriod, -pow2 - 1) * priceAfter;
        ++pow2;
      }
    }
    factorOnPeriodBar +=
        fixedRate * lastPeriod.getYearFraction() * -lastPow * Math.pow(factorOnPeriod, -lastPow - 1) * priceAfter;
    factorOnPeriodBar += -factorNextCoupon * Math.pow(factorOnPeriod, -factorNextCoupon - 1.0) * pvAtFirstCoupon;
    double yieldBar = 1.0d / ((double) bond.getFrequency().eventsPerYear()) * factorOnPeriodBar;
    return ValueDerivatives.of(price, DoubleArray.of(yieldBar));
  }

  /**
   * Calculates the yield of the fixed coupon bond product from dirty price.
   * <p>
   * The dirty price must be fractional.
   * If the analytic formula is not available, the yield is computed by solving
   * a root-finding problem with {@link #dirtyPriceFromYield(ResolvedFixedCouponBond, LocalDate, double)}.  
   * The result is also expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param dirtyPrice  the dirty price
   * @return the yield of the product 
   */
  public double yieldFromDirtyPrice(ResolvedFixedCouponBond bond, LocalDate settlementDate, double dirtyPrice) {
    if (bond.getYieldConvention().equals(JP_SIMPLE)) {
      double cleanPrice = cleanPriceFromDirtyPrice(bond, settlementDate, dirtyPrice);
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      return (bond.getFixedRate() + (bond.getRedemptionRatio() - cleanPrice) / maturity) / cleanPrice;
    }

    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    if (nCoupon == 1) {
      FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        FixedCouponBondPaymentPeriod payment = payments.get(payments.size() - 1);
        double absYield = (bond.getRedemptionRatio() + payment.getFixedRate() * payment.getYearFraction()) / dirtyPrice - 1d;
        if (absYield == 0) {
          return 0;
        }
        double annualMultiplier = Currency.AUD.equals(bond.getCurrency()) || Currency.CAD.equals(bond.getCurrency())
                ? 365.0 / (bond.getUnadjustedEndDate().toEpochDay() - settlementDate.toEpochDay())
                : ((double) bond.getFrequency().eventsPerYear()) / factorToNextCoupon(bond, settlementDate);
        return absYield * annualMultiplier;
      }
    }

    double yield = findYield(bond, settlementDate, dirtyPrice);
    return yield;
  }

  private double findYield(ResolvedFixedCouponBond bond, LocalDate settlementDate, double dirtyPrice) {
    if (rootFinder instanceof NewtonRaphsonSingleRootFinder) {
      NewtonRaphsonSingleRootFinder nrRootFinder = (NewtonRaphsonSingleRootFinder) rootFinder;
      final Function<Double, ValueDerivatives> priceResidualDerivative = new Function<Double, ValueDerivatives>() {
        @Override
        public ValueDerivatives apply(final Double y) {
          ValueDerivatives valueDerivative = dirtyPriceFromYieldAd(bond, settlementDate, y);
          return ValueDerivatives.of(valueDerivative.getValue() - dirtyPrice, valueDerivative.getDerivatives());
        }
      };
      double yield = nrRootFinder.getRootCombined(priceResidualDerivative, bond.getFixedRate());
      return yield; 
    }
    final Function<Double, Double> priceResidual = new Function<Double, Double>() {
      @Override
      public Double apply(final Double y) {
        return dirtyPriceFromYield(bond, settlementDate, y) - dirtyPrice;
      }
    };
    double[] range = ROOT_BRACKETER.getBracketedPoints(priceResidual, 0.00, 0.20);
    double yield = rootFinder.getRoot(priceResidual, range[0], range[1]);
    return yield;
  }

  /**
   * Calculates the yield of the fixed coupon bond product from dirty price and its derivative wrt the price.
   * <p>
   * The dirty price must be fractional.
   * If the analytic formula is not available, the yield is computed by solving
   * a root-finding problem with {@link #dirtyPriceFromYield(ResolvedFixedCouponBond, LocalDate, double)}.  
   * The result is also expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param dirtyPrice  the dirty price
   * @return the yield of the product 
   */
  public ValueDerivatives yieldFromDirtyPriceAd(ResolvedFixedCouponBond bond, LocalDate settlementDate, double dirtyPrice) {
    if (bond.getYieldConvention().equals(JP_SIMPLE)) {
      double cleanPrice = cleanPriceFromDirtyPrice(bond, settlementDate, dirtyPrice);
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double yield = (bond.getFixedRate() + (bond.getRedemptionRatio() - cleanPrice) / maturity) / cleanPrice;
      double priceBar =
          (-1.0d / maturity * cleanPrice - (bond.getFixedRate() + (bond.getRedemptionRatio() - cleanPrice) / maturity)) / (cleanPrice * cleanPrice);
      return ValueDerivatives.of(yield, DoubleArray.of(priceBar));
    }
    double yield = findYield(bond, settlementDate, dirtyPrice);
    ValueDerivatives priceDYield = dirtyPriceFromYieldAd(bond, settlementDate, yield);
    return ValueDerivatives.of(yield, DoubleArray.of(1.0 / priceDYield.getDerivative(0)));
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the modified duration of the fixed coupon bond product from yield.
   * <p>
   * The modified duration is defined as the minus of the first derivative of dirty price
   * with respect to yield, divided by the dirty price.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the modified duration of the product 
   */
  public double modifiedDurationFromYield(ResolvedFixedCouponBond bond, LocalDate settlementDate, double yield) {
    return modifiedDurationFromYield(bond, bond, settlementDate, yield);
  }

  /**
   * Calculates the modified duration of the fixed coupon bond product from yield.
   * <p>
   * The modified duration is defined as the minus of the first derivative of dirty price
   * with respect to yield, divided by the dirty price.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   *
   * @param bond  the product
   * @param bondPeriodCounter  the product used for the time to cash flows
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the modified duration of the product
   */
  public double modifiedDurationFromYield(ResolvedFixedCouponBond bond, ResolvedFixedCouponBond bondPeriodCounter,
        LocalDate settlementDate, double yield) {
    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if (nCoupon == 1) {
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        double couponPerYear = bond.getFrequency().eventsPerYear();
        double factor = factorToNextCoupon(bondPeriodCounter, settlementDate);
        return factor / couponPerYear / (1d + factor * yield / couponPerYear);
      }
    }
    if (yieldConv.equals(US_STREET) || yieldConv.equals(GB_BUMP_DMO) || yieldConv.equals(DE_BONDS)) {
      return modifiedDurationFromYieldStandard(bond, bondPeriodCounter, settlementDate, yield);
    }
    if (yieldConv.equals(JP_SIMPLE)) {
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      if (settlementDate.isAfter(maturityDate)) {
        return 0d;
      }
      double maturity = bondPeriodCounter.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double num = bond.getRedemptionRatio() + bond.getFixedRate() * maturity;
      double den = 1d + yield * maturity;
      double dirtyPrice = dirtyPriceFromCleanPrice(bond, settlementDate, num / den);
      return num * maturity / den / den / dirtyPrice;
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  /**
   * Calculates the modified duration of the fixed coupon bond product from yield and its derivative wrt to the yield.
   * <p>
   * The modified duration is defined as the minus of the first derivative of dirty price
   * with respect to yield, divided by the dirty price.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the modified duration of the product 
   */
  public ValueDerivatives modifiedDurationFromYieldAd(
      ResolvedFixedCouponBond bond,
      LocalDate settlementDate,
      double yield) {

    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if (nCoupon == 1) {
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        double couponPerYear = bond.getFrequency().eventsPerYear();
        double factor = factorToNextCoupon(bond, settlementDate);
        double md = factor / couponPerYear / (1d + factor * yield / couponPerYear);
        double yieldBar = -factor / couponPerYear /
            ((1d + factor * yield / couponPerYear) * (1d + factor * yield / couponPerYear)) * factor / couponPerYear;
        return ValueDerivatives.of(md, DoubleArray.of(yieldBar));
      }
    }
    if (yieldConv.equals(US_STREET) || yieldConv.equals(GB_BUMP_DMO) || yieldConv.equals(DE_BONDS)) {
      return modifiedDurationFromYieldStandardAd(bond, settlementDate, yield);
    }
    if (yieldConv.equals(JP_SIMPLE)) {
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      if (settlementDate.isAfter(maturityDate)) {
        double md = 0d;
        double yieldBar = 0.0;
        return ValueDerivatives.of(md, DoubleArray.of(yieldBar));
      }
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double num = bond.getRedemptionRatio() + bond.getFixedRate() * maturity;
      double den = 1d + yield * maturity;
      double cleanPrice = num / den;
      double dirtyPrice = dirtyPriceFromCleanPrice(bond, settlementDate, cleanPrice);
      double md = num * maturity / (den * den) / dirtyPrice;
      double mdBar = 1.0;
      double denBar = -2.0d * num * maturity / (den * den * den) / dirtyPrice * mdBar;
      double dirtyPriceBar = -md / dirtyPrice * mdBar;
      double cleanPriceBar = dirtyPriceBar;
      denBar += -cleanPrice / den * cleanPriceBar;
      double yieldBar = maturity * denBar;
      return ValueDerivatives.of(md, DoubleArray.of(yieldBar));
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  // Computes the modified duration with standard convention
  private double modifiedDurationFromYieldStandard(
      ResolvedFixedCouponBond bond,
      ResolvedFixedCouponBond bondPeriodCounter,
      LocalDate settlementDate,
      double yield) {

    int nbCoupon = bond.getPeriodicPayments().size();
    double couponPerYear = bond.getFrequency().eventsPerYear();
    double factorToNextCoupon = factorToNextCoupon(bondPeriodCounter, settlementDate);
    double factorOnPeriod = 1 + yield / couponPerYear;
    double nominal = bond.getNotional();
    double fixedRate = bond.getFixedRate();
    double mdAtFirstCoupon = 0d;
    double pvAtFirstCoupon = 0d;
    int pow = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        mdAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow + 1) *
            (pow + factorToNextCoupon) / couponPerYear;
        pvAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow);
        ++pow;
      }
    }
    mdAtFirstCoupon *= fixedRate * nominal;
    pvAtFirstCoupon *= fixedRate * nominal;
    mdAtFirstCoupon += nominal / Math.pow(factorOnPeriod, pow) * (pow - 1 + factorToNextCoupon) /
        couponPerYear;
    pvAtFirstCoupon += nominal / Math.pow(factorOnPeriod, pow - 1);
    double md = mdAtFirstCoupon / pvAtFirstCoupon;
    return md;
  }

  private ValueDerivatives modifiedDurationFromYieldStandardAd(
      ResolvedFixedCouponBond bond,
      LocalDate settlementDate,
      double yield) {

    int nbCoupon = bond.getPeriodicPayments().size();
    double couponPerYear = bond.getFrequency().eventsPerYear();
    double factorToNextCoupon = factorToNextCoupon(bond, settlementDate);
    double factorOnPeriod = 1 + yield / couponPerYear;
    double nominal = bond.getNotional();
    double fixedRate = bond.getFixedRate();
    double mdAtFirstCoupon = 0d;
    double pvAtFirstCoupon = 0d;
    int pow = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        mdAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow + 1) *
            (pow + factorToNextCoupon) / couponPerYear;
        pvAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow);
        ++pow;
      }
    }
    mdAtFirstCoupon *= fixedRate * nominal;
    pvAtFirstCoupon *= fixedRate * nominal;
    mdAtFirstCoupon += nominal / Math.pow(factorOnPeriod, pow) * (pow - 1 + factorToNextCoupon) /
        couponPerYear;
    pvAtFirstCoupon += nominal * Math.pow(factorOnPeriod, 1.0d - pow);
    double md = mdAtFirstCoupon / pvAtFirstCoupon;
    // Backward sweep
    double mdAtFirstCouponBar = 1.0d / pvAtFirstCoupon;
    double pvAtFirstCouponBar = -mdAtFirstCoupon / (pvAtFirstCoupon * pvAtFirstCoupon);
    double factorOnPeriodBar = nominal * (1.0d - pow) * Math.pow(factorOnPeriod, -pow) * pvAtFirstCouponBar;
    factorOnPeriodBar += nominal * -pow * Math.pow(factorOnPeriod, -pow - 1.0d) * (pow - 1 + factorToNextCoupon) /
        couponPerYear * mdAtFirstCouponBar;
    int pow2 = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        factorOnPeriodBar += period.getYearFraction() * (-pow2 - 1.0d) * Math.pow(factorOnPeriod, -pow2 - 2.0d) *
            (pow2 + factorToNextCoupon) / couponPerYear * fixedRate * nominal * mdAtFirstCouponBar;
        factorOnPeriodBar += period.getYearFraction() * -pow2 * Math.pow(factorOnPeriod, -pow2 - 1.0d) *
            fixedRate * nominal * pvAtFirstCouponBar;
        ++pow2;
      }
    }
    double yieldBar = 1.0d / couponPerYear * factorOnPeriodBar;
    return ValueDerivatives.of(md, DoubleArray.of(yieldBar));
  }

  /**
   * Calculates the Macaulay duration of the fixed coupon bond product from yield.
   * <p>
   * Macaulay defined an alternative way of weighting the future cash flows.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the modified duration of the product 
   */
  public double macaulayDurationFromYield(ResolvedFixedCouponBond bond, LocalDate settlementDate, double yield) {
    return macaulayDurationFromYield(bond, bond, settlementDate, yield);
  }

  /**
   * Calculates the Macaulay duration of the fixed coupon bond product from yield.
   * <p>
   * Macaulay defined an alternative way of weighting the future cash flows.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   *
   * @param bond  the product
   * @param bondPeriodCounter  the product used for the time to cash flows
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the modified duration of the product
   */
  public double macaulayDurationFromYield(ResolvedFixedCouponBond bond, ResolvedFixedCouponBond bondPeriodCounter,
        LocalDate settlementDate, double yield) {
    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if ((yieldConv.equals(US_STREET)) && (nCoupon == 1)) {
      return factorToNextCoupon(bondPeriodCounter, settlementDate) /
          bond.getFrequency().eventsPerYear();
    }
    if ((yieldConv.equals(US_STREET)) || (yieldConv.equals(GB_BUMP_DMO)) || (yieldConv.equals(DE_BONDS))) {
      return modifiedDurationFromYield(bond, bondPeriodCounter, settlementDate, yield) *
          (1d + yield / bond.getFrequency().eventsPerYear());
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  /**
   * Calculates the convexity of the fixed coupon bond product from yield.
   * <p>
   * The convexity is defined as the second derivative of dirty price with respect
   * to yield, divided by the dirty price.
   * <p>
   * The input yield must be fractional. The dirty price and its derivative are
   * computed for {@link FixedCouponBondYieldConvention}, and the result is expressed in fraction.
   * 
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @param yield  the yield
   * @return the convexity of the product 
   */
  public double convexityFromYield(ResolvedFixedCouponBond bond, LocalDate settlementDate, double yield) {
    ImmutableList<FixedCouponBondPaymentPeriod> payments = bond.getPeriodicPayments();
    int nCoupon = payments.size() - couponIndex(payments, settlementDate);
    FixedCouponBondYieldConvention yieldConv = bond.getYieldConvention();
    if (nCoupon == 1) {
      if (yieldConv.equals(US_STREET) || yieldConv.equals(DE_BONDS)) {
        double couponPerYear = bond.getFrequency().eventsPerYear();
        double factorToNextCoupon = factorToNextCoupon(bond, settlementDate);
        double timeToPay = factorToNextCoupon / couponPerYear;
        double disc = (1d + factorToNextCoupon * yield / couponPerYear);
        return 2d * timeToPay * timeToPay / (disc * disc);
      }
    }
    if (yieldConv.equals(US_STREET) || yieldConv.equals(GB_BUMP_DMO) || yieldConv.equals(DE_BONDS)) {
      return convexityFromYieldStandard(bond, settlementDate, yield);
    }
    if (yieldConv.equals(JP_SIMPLE)) {
      LocalDate maturityDate = bond.getUnadjustedEndDate();
      if (settlementDate.isAfter(maturityDate)) {
        return 0d;
      }
      double maturity = bond.getDayCount().relativeYearFraction(settlementDate, maturityDate);
      double num = bond.getRedemptionRatio() + bond.getFixedRate() * maturity;
      double den = 1d + yield * maturity;
      double dirtyPrice = dirtyPriceFromCleanPrice(bond, settlementDate, num / den);
      return 2d * num * pow2(maturity) * Math.pow(den, -3) / dirtyPrice;
    }
    throw new UnsupportedOperationException("The convention " + yieldConv.name() + " is not supported.");
  }

  // assumes notional and coupon rate are constant across the payments.
  private double convexityFromYieldStandard(
      ResolvedFixedCouponBond bond,
      LocalDate settlementDate,
      double yield) {

    int nbCoupon = bond.getPeriodicPayments().size();
    double couponPerYear = bond.getFrequency().eventsPerYear();
    double factorToNextCoupon = factorToNextCoupon(bond, settlementDate);
    double factorOnPeriod = 1 + yield / couponPerYear;
    double nominal = bond.getNotional();
    double fixedRate = bond.getFixedRate();
    double cvAtFirstCoupon = 0;
    double pvAtFirstCoupon = 0;
    int pow = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; loopcpn++) {
      FixedCouponBondPaymentPeriod period = bond.getPeriodicPayments().get(loopcpn);
      if ((period.hasExCouponPeriod() && !settlementDate.isAfter(period.getDetachmentDate())) ||
          (!period.hasExCouponPeriod() && period.getPaymentDate().isAfter(settlementDate))) {
        cvAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow + 2) *
            (pow + factorToNextCoupon) * (pow + factorToNextCoupon + 1);
        pvAtFirstCoupon += period.getYearFraction() / Math.pow(factorOnPeriod, pow);
        ++pow;
      }
    }
    cvAtFirstCoupon *= fixedRate * nominal / (couponPerYear * couponPerYear);
    pvAtFirstCoupon *= fixedRate * nominal;
    cvAtFirstCoupon += nominal / Math.pow(factorOnPeriod, pow + 1) * (pow - 1 + factorToNextCoupon) *
        (pow + factorToNextCoupon) / (couponPerYear * couponPerYear);
    pvAtFirstCoupon += nominal / Math.pow(factorOnPeriod, pow - 1);
    final double pv = pvAtFirstCoupon * Math.pow(factorOnPeriod, -factorToNextCoupon);
    final double cv = cvAtFirstCoupon * Math.pow(factorOnPeriod, -factorToNextCoupon) / pv;
    return cv;
  }

  //-------------------------------------------------------------------------
  /**
   * Calculates the accrual factor to the next coupon.
   *
   * @param bond  the product
   * @param settlementDate  the settlement date
   * @return the accrual factor to the next coupon
   */
  public double factorToNextCoupon(ResolvedFixedCouponBond bond, LocalDate settlementDate) {
    if (bond.getPeriodicPayments().get(0).getStartDate().isAfter(settlementDate)) {
      return 0d;
    }
    int couponIndex = couponIndex(bond.getPeriodicPayments(), settlementDate);
    double factorSpot = accruedYearFraction(bond, settlementDate);
    double factorPeriod = bond.getPeriodicPayments().get(couponIndex).getYearFraction();
    return (factorPeriod - factorSpot) * ((double) bond.getFrequency().eventsPerYear());
  }

  private int couponIndex(ImmutableList<FixedCouponBondPaymentPeriod> list, LocalDate date) {
    int nbCoupon = list.size();
    int couponIndex = 0;
    for (int loopcpn = 0; loopcpn < nbCoupon; ++loopcpn) {
      if (list.get(loopcpn).getEndDate().isAfter(date)) {
        couponIndex = loopcpn;
        break;
      }
    }
    return couponIndex;
  }

  //-------------------------------------------------------------------------
  private CurrencyAmount presentValueCoupon(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      LocalDate referenceDate) {

    double total = 0d;
    for (FixedCouponBondPaymentPeriod period : bond.getPeriodicPayments()) {
      if (period.getDetachmentDate().isAfter(referenceDate)) {
        total += periodPricer.presentValue(period, discountFactors);
      }
    }
    return CurrencyAmount.of(bond.getCurrency(), total);
  }

  private CurrencyAmount presentValueCouponFromZSpread(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate referenceDate) {

    double total = 0d;
    for (FixedCouponBondPaymentPeriod period : bond.getPeriodicPayments()) {
      if (!period.getDetachmentDate().isBefore(referenceDate)) {
        total += periodPricer.presentValueWithSpread(period, discountFactors, zSpread, compoundedRateType, periodsPerYear);
      }
    }
    return CurrencyAmount.of(bond.getCurrency(), total);
  }

  //-------------------------------------------------------------------------
  private PointSensitivityBuilder presentValueSensitivityCoupon(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      LocalDate referenceDate) {

    PointSensitivityBuilder builder = PointSensitivityBuilder.none();
    for (FixedCouponBondPaymentPeriod period : bond.getPeriodicPayments()) {
      if (period.getDetachmentDate().isAfter(referenceDate)) {
        builder = builder.combinedWith(periodPricer.presentValueSensitivity(period, discountFactors));
      }
    }
    return builder;
  }

  private PointSensitivityBuilder presentValueSensitivityCouponFromZSpread(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear,
      LocalDate referenceDate) {

    PointSensitivityBuilder builder = PointSensitivityBuilder.none();
    for (FixedCouponBondPaymentPeriod period : bond.getPeriodicPayments()) {
      if (period.getDetachmentDate().isAfter(referenceDate)) {
        builder = builder.combinedWith(periodPricer.presentValueSensitivityWithSpread(
            period, discountFactors, zSpread, compoundedRateType, periodsPerYear));
      }
    }
    return builder;
  }

  private PointSensitivityBuilder presentValueSensitivityNominal(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors) {

    Payment nominal = bond.getNominalPayment();
    PointSensitivityBuilder pt = nominalPricer.presentValueSensitivity(nominal, discountFactors.getDiscountFactors());
    if (pt instanceof ZeroRateSensitivity) {
      return IssuerCurveZeroRateSensitivity.of((ZeroRateSensitivity) pt, discountFactors.getLegalEntityGroup());
    }
    return pt; // NoPointSensitivity
  }

  private PointSensitivityBuilder presentValueSensitivityNominalFromZSpread(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    Payment nominal = bond.getNominalPayment();
    PointSensitivityBuilder pt = nominalPricer.presentValueSensitivityWithSpread(
        nominal, discountFactors.getDiscountFactors(), zSpread, compoundedRateType, periodsPerYear);
    if (pt instanceof ZeroRateSensitivity) {
      return IssuerCurveZeroRateSensitivity.of((ZeroRateSensitivity) pt, discountFactors.getLegalEntityGroup());
    }
    return pt; // NoPointSensitivity
  }

  //-------------------------------------------------------------------------
  // compute pv of coupon payment(s) s.t. referenceDate1 < coupon <= referenceDate2
  double presentValueCoupon(
      ResolvedFixedCouponBond bond,
      IssuerCurveDiscountFactors discountFactors,
      LocalDate referenceDate1,
      LocalDate referenceDate2) {

    double pvDiff = 0d;
    for (FixedCouponBondPaymentPeriod period : bond.getPeriodicPayments()) {
      if (period.getDetachmentDate().isAfter(referenceDate1) && !period.getDetachmentDate().isAfter(referenceDate2)) {
        pvDiff += periodPricer.presentValue(period, discountFactors);
      }
    }
    return pvDiff;
  }

  // compute pv of coupon payment(s) s.t. referenceDate1 < coupon <= referenceDate2
  double presentValueCouponWithZSpread(
      ResolvedFixedCouponBond expanded,
      IssuerCurveDiscountFactors discountFactors,
      LocalDate referenceDate1,
      LocalDate referenceDate2,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodsPerYear) {

    double pvDiff = 0d;
    for (FixedCouponBondPaymentPeriod period : expanded.getPeriodicPayments()) {
      if (period.getDetachmentDate().isAfter(referenceDate1) && !period.getDetachmentDate().isAfter(referenceDate2)) {
        pvDiff += periodPricer.presentValueWithSpread(period, discountFactors, zSpread, compoundedRateType, periodsPerYear);
      }
    }
    return pvDiff;
  }

  //-------------------------------------------------------------------------
  // extracts the repo curve discount factors for the bond
  static RepoCurveDiscountFactors repoCurveDf(ResolvedFixedCouponBond bond, LegalEntityDiscountingProvider provider) {
    return provider.repoCurveDiscountFactors(bond.getSecurityId(), bond.getLegalEntityId(), bond.getCurrency());
  }

  // extracts the issuer curve discount factors for the bond
  static IssuerCurveDiscountFactors issuerCurveDf(ResolvedFixedCouponBond bond, LegalEntityDiscountingProvider provider) {
    return provider.issuerCurveDiscountFactors(bond.getLegalEntityId(), bond.getCurrency());
  }

}
