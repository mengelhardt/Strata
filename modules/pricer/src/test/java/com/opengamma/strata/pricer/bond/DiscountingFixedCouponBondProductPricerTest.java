/*
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.bond;

import static com.opengamma.strata.basics.currency.Currency.EUR;
import static com.opengamma.strata.basics.currency.Currency.GBP;
import static com.opengamma.strata.basics.date.BusinessDayConventions.MODIFIED_FOLLOWING;
import static com.opengamma.strata.basics.date.DayCounts.ACT_365F;
import static com.opengamma.strata.basics.date.HolidayCalendarIds.GBLO;
import static com.opengamma.strata.basics.date.HolidayCalendarIds.JPTO;
import static com.opengamma.strata.basics.date.HolidayCalendarIds.SAT_SUN;
import static com.opengamma.strata.collect.TestHelper.date;
import static com.opengamma.strata.pricer.CompoundedRateType.CONTINUOUS;
import static com.opengamma.strata.pricer.CompoundedRateType.PERIODIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.offset;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.CurrencyAmount;
import com.opengamma.strata.basics.date.BusinessDayAdjustment;
import com.opengamma.strata.basics.date.BusinessDayConventions;
import com.opengamma.strata.basics.date.DayCount;
import com.opengamma.strata.basics.date.DayCounts;
import com.opengamma.strata.basics.date.DaysAdjustment;
import com.opengamma.strata.basics.date.HolidayCalendarId;
import com.opengamma.strata.basics.date.HolidayCalendarIds;
import com.opengamma.strata.basics.schedule.Frequency;
import com.opengamma.strata.basics.schedule.PeriodicSchedule;
import com.opengamma.strata.basics.schedule.StubConvention;
import com.opengamma.strata.basics.value.ValueDerivatives;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.collect.tuple.Pair;
import com.opengamma.strata.market.curve.CurveMetadata;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.Curves;
import com.opengamma.strata.market.curve.InterpolatedNodalCurve;
import com.opengamma.strata.market.curve.LegalEntityGroup;
import com.opengamma.strata.market.curve.RepoGroup;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolator;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolators;
import com.opengamma.strata.market.param.CurrencyParameterSensitivities;
import com.opengamma.strata.market.sensitivity.PointSensitivityBuilder;
import com.opengamma.strata.pricer.DiscountFactors;
import com.opengamma.strata.pricer.DiscountingPaymentPricer;
import com.opengamma.strata.pricer.ZeroRateDiscountFactors;
import com.opengamma.strata.pricer.sensitivity.RatesFiniteDifferenceSensitivityCalculator;
import com.opengamma.strata.product.LegalEntityId;
import com.opengamma.strata.product.SecurityId;
import com.opengamma.strata.product.bond.FixedCouponBond;
import com.opengamma.strata.product.bond.FixedCouponBondPaymentPeriod;
import com.opengamma.strata.product.bond.FixedCouponBondYieldConvention;
import com.opengamma.strata.product.bond.ResolvedFixedCouponBond;

/**
 * Test {@link DiscountingFixedCouponBondProductPricer}
 */
public class DiscountingFixedCouponBondProductPricerTest {

  private static final ReferenceData REF_DATA = ReferenceData.standard();

  // fixed coupon bond
  private static final SecurityId SECURITY_ID = SecurityId.of("OG-Ticker", "GOVT1-BOND1");
  private static final LegalEntityId ISSUER_ID = LegalEntityId.of("OG-Ticker", "GOVT1");
  private static final LocalDate VAL_DATE = date(2016, 4, 25);
  private static final FixedCouponBondYieldConvention YIELD_CONVENTION = FixedCouponBondYieldConvention.DE_BONDS;
  private static final double NOTIONAL = 1.0e7;
  private static final double FIXED_RATE = 0.015;
  private static final HolidayCalendarId EUR_CALENDAR = HolidayCalendarIds.EUTA;
  private static final DaysAdjustment DATE_OFFSET = DaysAdjustment.ofBusinessDays(3, EUR_CALENDAR);
  private static final DayCount DAY_COUNT = DayCounts.ACT_365F;
  private static final LocalDate START_DATE = LocalDate.of(2015, 4, 12);
  private static final LocalDate END_DATE = LocalDate.of(2025, 4, 12);
  private static final BusinessDayAdjustment BUSINESS_ADJUST =
      BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, EUR_CALENDAR);
  private static final PeriodicSchedule PERIOD_SCHEDULE = PeriodicSchedule.of(
      START_DATE, END_DATE, Frequency.P6M, BUSINESS_ADJUST, StubConvention.SHORT_INITIAL, false);
  private static final DaysAdjustment EX_COUPON = DaysAdjustment.ofBusinessDays(-5, EUR_CALENDAR, BUSINESS_ADJUST);
  /** nonzero ex-coupon period */
  private static final ResolvedFixedCouponBond PRODUCT = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DAY_COUNT)
      .fixedRate(FIXED_RATE)
      .legalEntityId(ISSUER_ID)
      .currency(EUR)
      .notional(NOTIONAL)
      .accrualSchedule(PERIOD_SCHEDULE)
      .settlementDateOffset(DATE_OFFSET)
      .yieldConvention(YIELD_CONVENTION)
      .exCouponPeriod(EX_COUPON)
      .build()
      .resolve(REF_DATA);
  /** no ex-coupon period */
  private static final ResolvedFixedCouponBond PRODUCT_NO_EXCOUPON = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DAY_COUNT)
      .fixedRate(FIXED_RATE)
      .legalEntityId(ISSUER_ID)
      .currency(EUR)
      .notional(NOTIONAL)
      .accrualSchedule(PERIOD_SCHEDULE)
      .settlementDateOffset(DATE_OFFSET)
      .yieldConvention(YIELD_CONVENTION)
      .build()
      .resolve(REF_DATA);

  // rates provider
  private static final CurveInterpolator INTERPOLATOR = CurveInterpolators.LINEAR;
  private static final CurveName NAME_REPO = CurveName.of("TestRepoCurve");
  private static final CurveMetadata METADATA_REPO = Curves.zeroRates(NAME_REPO, ACT_365F);
  private static final InterpolatedNodalCurve CURVE_REPO = InterpolatedNodalCurve.of(
      METADATA_REPO, DoubleArray.of(0.1, 2.0, 10.0), DoubleArray.of(0.05, 0.06, 0.09), INTERPOLATOR);
  private static final DiscountFactors DSC_FACTORS_REPO = ZeroRateDiscountFactors.of(EUR, VAL_DATE, CURVE_REPO);
  private static final RepoGroup GROUP_REPO = RepoGroup.of("GOVT1 BOND1");
  private static final CurveName NAME_ISSUER = CurveName.of("TestIssuerCurve");
  private static final CurveMetadata METADATA_ISSUER = Curves.zeroRates(NAME_ISSUER, ACT_365F);
  private static final InterpolatedNodalCurve CURVE_ISSUER = InterpolatedNodalCurve.of(
      METADATA_ISSUER, DoubleArray.of(0.2, 9.0, 15.0), DoubleArray.of(0.03, 0.05, 0.13), INTERPOLATOR);
  private static final DiscountFactors DSC_FACTORS_ISSUER = ZeroRateDiscountFactors.of(EUR, VAL_DATE, CURVE_ISSUER);
  private static final LegalEntityGroup GROUP_ISSUER = LegalEntityGroup.of("GOVT1");
  private static final LegalEntityDiscountingProvider PROVIDER = ImmutableLegalEntityDiscountingProvider.builder()
      .issuerCurves(ImmutableMap.of(Pair.of(GROUP_ISSUER, EUR), DSC_FACTORS_ISSUER))
      .issuerCurveGroups(ImmutableMap.of(ISSUER_ID, GROUP_ISSUER))
      .repoCurves(ImmutableMap.of(Pair.of(GROUP_REPO, EUR), DSC_FACTORS_REPO))
      .repoCurveSecurityGroups(ImmutableMap.of(SECURITY_ID, GROUP_REPO))
      .valuationDate(VAL_DATE)
      .build();

  private static final double Z_SPREAD = 0.035;
  private static final int PERIOD_PER_YEAR = 4;
  private static final double TOL = 1.0e-12;
  private static final double EPS = 1.0e-7;

  // pricers
  private static final DiscountingFixedCouponBondProductPricer PRICER = DiscountingFixedCouponBondProductPricer.DEFAULT;
  private static final DiscountingPaymentPricer PRICER_NOMINAL = DiscountingPaymentPricer.DEFAULT;
  private static final DiscountingFixedCouponBondPaymentPeriodPricer PRICER_COUPON =
      DiscountingFixedCouponBondPaymentPeriodPricer.DEFAULT;
  private static final RatesFiniteDifferenceSensitivityCalculator FD_CAL =
      new RatesFiniteDifferenceSensitivityCalculator(EPS);

  //-------------------------------------------------------------------------
  @Test
  public void test_presentValue() {
    CurrencyAmount computed = PRICER.presentValue(PRODUCT, PROVIDER);
    CurrencyAmount expected = PRICER_NOMINAL.presentValue(PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvCupon += PRICER_COUPON.presentValue(payment, IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER));
    }
    expected = expected.plus(pvCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  @Test
  public void test_presentValueWithZSpread_continuous() {
    CurrencyAmount computed = PRICER.presentValueWithZSpread(PRODUCT, PROVIDER, Z_SPREAD, CONTINUOUS, 0);
    CurrencyAmount expected = PRICER_NOMINAL.presentValueWithSpread(
        PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER, Z_SPREAD, CONTINUOUS, 0);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvcCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvcCupon += PRICER_COUPON.presentValueWithSpread(payment,
          IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER), Z_SPREAD, CONTINUOUS, 0);
    }
    expected = expected.plus(pvcCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  @Test
  public void test_presentValueWithZSpread_periodic() {
    CurrencyAmount computed = PRICER.presentValueWithZSpread(
        PRODUCT, PROVIDER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyAmount expected = PRICER_NOMINAL.presentValueWithSpread(
        PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvcCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvcCupon += PRICER_COUPON.presentValueWithSpread(payment,
          IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER), Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    }
    expected = expected.plus(pvcCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  @Test
  public void test_presentValue_noExcoupon() {
    CurrencyAmount computed = PRICER.presentValue(PRODUCT_NO_EXCOUPON, PROVIDER);
    CurrencyAmount expected = PRICER_NOMINAL.presentValue(PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvcCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvcCupon += PRICER_COUPON.presentValue(payment, IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER));
    }
    expected = expected.plus(pvcCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  @Test
  public void test_presentValueWithZSpread_continuous_noExcoupon() {
    CurrencyAmount computed = PRICER.presentValueWithZSpread(
        PRODUCT_NO_EXCOUPON, PROVIDER, Z_SPREAD, CONTINUOUS, 0);
    CurrencyAmount expected = PRICER_NOMINAL.presentValueWithSpread(
        PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER, Z_SPREAD, CONTINUOUS, 0);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvcCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvcCupon += PRICER_COUPON.presentValueWithSpread(payment,
          IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER), Z_SPREAD, CONTINUOUS, 0);
    }
    expected = expected.plus(pvcCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  @Test
  public void test_presentValueWithZSpread_periodic_noExcoupon() {
    CurrencyAmount computed = PRICER.presentValueWithZSpread(
        PRODUCT_NO_EXCOUPON, PROVIDER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyAmount expected = PRICER_NOMINAL.presentValueWithSpread(
        PRODUCT.getNominalPayment(), DSC_FACTORS_ISSUER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    int size = PRODUCT.getPeriodicPayments().size();
    double pvcCupon = 0d;
    for (int i = 2; i < size; ++i) {
      FixedCouponBondPaymentPeriod payment = PRODUCT.getPeriodicPayments().get(i);
      pvcCupon += PRICER_COUPON.presentValueWithSpread(payment,
          IssuerCurveDiscountFactors.of(DSC_FACTORS_ISSUER, GROUP_ISSUER), Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    }
    expected = expected.plus(pvcCupon);
    assertThat(computed.getCurrency()).isEqualTo(EUR);
    assertThat(computed.getAmount()).isCloseTo(expected.getAmount(), offset(NOTIONAL * TOL));
  }

  //-------------------------------------------------------------------------
  @Test
  public void test_dirtyPriceFromCurves() {
    double computed = PRICER.dirtyPriceFromCurves(PRODUCT, PROVIDER, REF_DATA);
    CurrencyAmount pv = PRICER.presentValue(PRODUCT, PROVIDER);
    LocalDate settlement = DATE_OFFSET.adjust(VAL_DATE, REF_DATA);
    double df = DSC_FACTORS_REPO.discountFactor(settlement);
    assertThat(computed).isEqualTo(pv.getAmount() / df / NOTIONAL);
  }

  @Test
  public void test_dirtyPriceFromCurvesWithZSpread_continuous() {
    double computed = PRICER.dirtyPriceFromCurvesWithZSpread(
        PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, CONTINUOUS, 0);
    CurrencyAmount pv = PRICER.presentValueWithZSpread(PRODUCT, PROVIDER, Z_SPREAD, CONTINUOUS, 0);
    LocalDate settlement = DATE_OFFSET.adjust(VAL_DATE, REF_DATA);
    double df = DSC_FACTORS_REPO.discountFactor(settlement);
    assertThat(computed).isEqualTo(pv.getAmount() / df / NOTIONAL);
  }

  @Test
  public void test_dirtyPriceFromCurvesWithZSpread_periodic() {
    double computed = PRICER.dirtyPriceFromCurvesWithZSpread(
        PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyAmount pv = PRICER.presentValueWithZSpread(
        PRODUCT, PROVIDER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    LocalDate settlement = DATE_OFFSET.adjust(VAL_DATE, REF_DATA);
    double df = DSC_FACTORS_REPO.discountFactor(settlement);
    assertThat(computed).isEqualTo(pv.getAmount() / df / NOTIONAL);
  }

  @Test
  public void test_dirtyPriceFromCleanPrice_cleanPriceFromDirtyPrice() {
    double dirtyPrice = PRICER.dirtyPriceFromCurves(PRODUCT, PROVIDER, REF_DATA);
    LocalDate settlement = DATE_OFFSET.adjust(VAL_DATE, REF_DATA);
    double cleanPrice = PRICER.cleanPriceFromDirtyPrice(PRODUCT, settlement, dirtyPrice);
    double accruedInterest = PRICER.accruedInterest(PRODUCT, settlement);
    assertThat(cleanPrice).isCloseTo(dirtyPrice - accruedInterest / NOTIONAL, offset(NOTIONAL * TOL));
    double dirtyPriceRe = PRICER.dirtyPriceFromCleanPrice(PRODUCT, settlement, cleanPrice);
    assertThat(dirtyPriceRe).isCloseTo(dirtyPrice, offset(TOL));
  }

  @Test
  public void test_dirtyPriceFromCleanPrice_ukNewIssue() {
    BusinessDayAdjustment gbloModFollow = BusinessDayAdjustment.of(MODIFIED_FOLLOWING, GBLO);
    ResolvedFixedCouponBond bond = FixedCouponBond.builder()
        .securityId(SECURITY_ID)
        .dayCount(DayCounts.ACT_ACT_ICMA)
        .fixedRate(0.00875)
        .legalEntityId(ISSUER_ID)
        .currency(GBP)
        .notional(NOTIONAL)
        .accrualSchedule(PeriodicSchedule.of(
            date(2019, 6, 19),
            date(2029, 10, 22),
            Frequency.P6M,
            gbloModFollow,
            StubConvention.SMART_INITIAL,
            false))
        .settlementDateOffset(DaysAdjustment.ofBusinessDays(1, GBLO))
        .yieldConvention(FixedCouponBondYieldConvention.GB_BUMP_DMO)
        .exCouponPeriod(DaysAdjustment.ofCalendarDays(-8, gbloModFollow))
        .build()
        .resolve(REF_DATA);
    LocalDate settlement = LocalDate.of(2019, 6, 20);
    double dirtyPrice = PRICER.dirtyPriceFromCleanPrice(bond, settlement, 0.993);
    assertThat(dirtyPrice).isCloseTo(0.99302391d, offset(1e-8));
    double yield = PRICER.yieldFromDirtyPrice(bond, settlement, dirtyPrice);
    assertThat(yield).isCloseTo(0.00946254d, offset(1e-8));
    double modDur = PRICER.modifiedDurationFromYield(bond, settlement, yield);
    assertThat(modDur).isCloseTo(9.85841456d, offset(EPS));

    double cleanPrice = PRICER.cleanPriceFromDirtyPrice(bond, settlement, dirtyPrice);
    double accruedInterest = PRICER.accruedInterest(bond, settlement);
    assertThat(cleanPrice).isCloseTo(dirtyPrice - accruedInterest / NOTIONAL, offset(NOTIONAL * TOL));
  }

  //-------------------------------------------------------------------------
  @Test
  public void test_zSpreadFromCurvesAndPV_continuous() {
    double dirtyPrice = PRICER.dirtyPriceFromCurvesWithZSpread(
        PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, CONTINUOUS, 0);
    double computed = PRICER.zSpreadFromCurvesAndDirtyPrice(
        PRODUCT, PROVIDER, REF_DATA, dirtyPrice, CONTINUOUS, 0);
    assertThat(computed).isCloseTo(Z_SPREAD, offset(TOL));
  }

  @Test
  public void test_zSpreadFromCurvesAndPV_periodic() {
    double dirtyPrice = PRICER.dirtyPriceFromCurvesWithZSpread(
        PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    double computed = PRICER.zSpreadFromCurvesAndDirtyPrice(
        PRODUCT, PROVIDER, REF_DATA, dirtyPrice, PERIODIC, PERIOD_PER_YEAR);
    assertThat(computed).isCloseTo(Z_SPREAD, offset(TOL));
  }

  //-------------------------------------------------------------------------
  @Test
  public void test_presentValueSensitivity() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivity(PRODUCT, PROVIDER);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER, p -> PRICER.presentValue(PRODUCT, p));
    assertThat(computed.equalWithTolerance(expected, 30d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_presentValueSensitivityWithZSpread_continuous() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivityWithZSpread(PRODUCT, PROVIDER, Z_SPREAD, CONTINUOUS,
        0);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(
        PROVIDER, p -> PRICER.presentValueWithZSpread(PRODUCT, p, Z_SPREAD, CONTINUOUS, 0));
    assertThat(computed.equalWithTolerance(expected, 20d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_presentValueSensitivityWithZSpread_periodic() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivityWithZSpread(
        PRODUCT, PROVIDER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER,
        p -> PRICER.presentValueWithZSpread(PRODUCT, p, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR));
    assertThat(computed.equalWithTolerance(expected, 20d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_presentValueProductSensitivity_noExcoupon() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivity(PRODUCT_NO_EXCOUPON, PROVIDER);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(
        PROVIDER, p -> PRICER.presentValue(PRODUCT_NO_EXCOUPON, p));
    assertThat(computed.equalWithTolerance(expected, 30d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_presentValueSensitivityWithZSpread_continuous_noExcoupon() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivityWithZSpread(
        PRODUCT_NO_EXCOUPON, PROVIDER, Z_SPREAD, CONTINUOUS, 0);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER,
        p -> PRICER.presentValueWithZSpread(PRODUCT_NO_EXCOUPON, p, Z_SPREAD, CONTINUOUS, 0));
    assertThat(computed.equalWithTolerance(expected, 20d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_presentValueSensitivityWithZSpread_periodic_noExcoupon() {
    PointSensitivityBuilder point = PRICER.presentValueSensitivityWithZSpread(
        PRODUCT_NO_EXCOUPON, PROVIDER, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER,
        p -> PRICER.presentValueWithZSpread(PRODUCT_NO_EXCOUPON, p, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR));
    assertThat(computed.equalWithTolerance(expected, 20d * NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_dirtyPriceSensitivity() {
    PointSensitivityBuilder point = PRICER.dirtyPriceSensitivity(PRODUCT, PROVIDER, REF_DATA);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(
        PROVIDER, p -> CurrencyAmount.of(EUR, PRICER.dirtyPriceFromCurves(PRODUCT, p, REF_DATA)));
    assertThat(computed.equalWithTolerance(expected, NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_dirtyPriceSensitivity_forward() {
    LocalDate forwardDate = VAL_DATE.plusYears(1);
    PointSensitivityBuilder point = PRICER.dirtyPriceSensitivity(PRODUCT, PROVIDER, forwardDate);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(
        PROVIDER, p -> CurrencyAmount.of(EUR, PRICER.dirtyPriceFromCurves(PRODUCT, p, forwardDate)));
    assertThat(computed.equalWithTolerance(expected, 1.0E-5)).isTrue();
  }

  @Test
  public void test_dirtyPriceSensitivityWithZspread_continuous() {
    PointSensitivityBuilder point =
        PRICER.dirtyPriceSensitivityWithZspread(PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, CONTINUOUS, 0);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER, p -> CurrencyAmount.of(
        EUR, PRICER.dirtyPriceFromCurvesWithZSpread(PRODUCT, p, REF_DATA, Z_SPREAD, CONTINUOUS, 0)));
    assertThat(computed.equalWithTolerance(expected, NOTIONAL * EPS)).isTrue();
  }

  @Test
  public void test_dirtyPriceSensitivityWithZspread_periodic() {
    PointSensitivityBuilder point = PRICER.dirtyPriceSensitivityWithZspread(
        PRODUCT, PROVIDER, REF_DATA, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR);
    CurrencyParameterSensitivities computed = PROVIDER.parameterSensitivity(point.build());
    CurrencyParameterSensitivities expected = FD_CAL.sensitivity(PROVIDER, p -> CurrencyAmount.of(EUR, PRICER
        .dirtyPriceFromCurvesWithZSpread(PRODUCT, p, REF_DATA, Z_SPREAD, PERIODIC, PERIOD_PER_YEAR)));
    assertThat(computed.equalWithTolerance(expected, NOTIONAL * EPS)).isTrue();
  }

  //-------------------------------------------------------------------------
  @Test
  public void test_accruedInterest() {
    // settle before start
    LocalDate settleDate1 = START_DATE.minusDays(5);
    double accruedInterest1 = PRICER.accruedInterest(PRODUCT, settleDate1);
    assertThat(accruedInterest1).isEqualTo(0d);
    // settle between endDate and endDate -lag
    LocalDate settleDate2 = date(2015, 10, 8);
    double accruedInterest2 = PRICER.accruedInterest(PRODUCT, settleDate2);
    assertThat(accruedInterest2).isCloseTo(-4.0 / 365.0 * FIXED_RATE * NOTIONAL, offset(EPS));
    // normal
    LocalDate settleDate3 = date(2015, 4, 18); // not adjusted
    ResolvedFixedCouponBond product = FixedCouponBond.builder()
        .securityId(SECURITY_ID)
        .dayCount(DAY_COUNT)
        .fixedRate(FIXED_RATE)
        .legalEntityId(ISSUER_ID)
        .currency(EUR)
        .notional(NOTIONAL)
        .accrualSchedule(PERIOD_SCHEDULE)
        .settlementDateOffset(DATE_OFFSET)
        .yieldConvention(YIELD_CONVENTION)
        .exCouponPeriod(DaysAdjustment.NONE)
        .build()
        .resolve(REF_DATA);
    double accruedInterest3 = PRICER.accruedInterest(product, settleDate3);
    assertThat(accruedInterest3).isCloseTo(6.0 / 365.0 * FIXED_RATE * NOTIONAL, offset(EPS));
  }

  //-------------------------------------------------------------------------
  /* US Street convention */
  private static final LocalDate START_US = date(2006, 11, 15);
  private static final LocalDate END_US = START_US.plusYears(10);
  private static final PeriodicSchedule SCHEDULE_US = PeriodicSchedule.of(START_US, END_US, Frequency.P6M,
      BusinessDayAdjustment.of(BusinessDayConventions.FOLLOWING, SAT_SUN),
      StubConvention.SHORT_INITIAL, false);
  private static final ResolvedFixedCouponBond PRODUCT_US = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DayCounts.ACT_ACT_ICMA)
      .fixedRate(0.04625)
      .legalEntityId(ISSUER_ID)
      .currency(Currency.USD)
      .notional(100)
      .accrualSchedule(SCHEDULE_US)
      .settlementDateOffset(DaysAdjustment.ofBusinessDays(3, SAT_SUN))
      .yieldConvention(FixedCouponBondYieldConvention.US_STREET)
      .exCouponPeriod(DaysAdjustment.NONE)
      .build()
      .resolve(REF_DATA);
  private static final ResolvedFixedCouponBond PRODUCT_US_0 = PRODUCT_US.toBuilder().fixedRate(0.0d).build();
  private static final LocalDate VALUATION_US = date(2011, 8, 18);
  private static final LocalDate SETTLEMENT_US = PRODUCT_US.getSettlementDateOffset().adjust(VALUATION_US, REF_DATA);
  private static final LocalDate VALUATION_LAST_US = date(2016, 6, 3);
  private static final LocalDate SETTLEMENT_LAST_US = PRODUCT_US.getSettlementDateOffset().adjust(VALUATION_LAST_US, REF_DATA);
  private static final double YIELD_US = 0.04;

  @Test
  public void dirtyPriceFromYieldUS() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    assertThat(dirtyPrice).isCloseTo(1.0417352500524246, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_US, SETTLEMENT_US, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_US, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldUS_AD() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    ValueDerivatives dirtyPriceAd = PRICER.dirtyPriceFromYieldAd(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    assertThat(dirtyPrice).isCloseTo(dirtyPriceAd.getValue(), offset(TOL));
    double shift = 1.0E-8;
    double dirtyPriceP = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US + shift);
    double derivativeExpected = (dirtyPriceP - dirtyPrice) / shift;
    assertThat(derivativeExpected).isCloseTo(dirtyPriceAd.getDerivative(0), offset(1.0E-6));
  }

  // Check price from yield when coupon is 0.
  @Test
  public void dirtyPriceFromYieldUS0() {
    double dirtyPrice0 = PRICER.dirtyPriceFromYield(PRODUCT_US_0, SETTLEMENT_US, 0.0d);
    assertThat(dirtyPrice0).isCloseTo(1.0d, offset(TOL));
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_US_0, SETTLEMENT_US, YIELD_US);
    assertThat(dirtyPrice).isCloseTo(0.8129655023939295, offset(TOL)); // Previous run
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_US_0, SETTLEMENT_US, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_US, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldUSLastPeriod() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    assertThat(dirtyPrice).isCloseTo(1.005635683760684, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_US, SETTLEMENT_LAST_US, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_US, offset(TOL));
  }

  @Test
  public void modifiedDurationFromYieldUS() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldUS_AD() {
    double shift = 1.0E-6;
    double md = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    ValueDerivatives mdAd = PRICER.modifiedDurationFromYieldAd(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    assertThat(mdAd.getValue()).isCloseTo(md, offset(EPS));
    double mdP = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US + shift);
    double derivativeExpect = (mdP - md) / shift;
    assertThat(mdAd.getDerivative(0)).isCloseTo(derivativeExpect, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldUSLastPeriod() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldUSLastPeriod_AD() {
    double shift = 1.0E-6;
    double md = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    ValueDerivatives mdAd = PRICER.modifiedDurationFromYieldAd(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    assertThat(mdAd.getValue()).isCloseTo(md, offset(EPS));
    double mdP = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US + shift);
    double derivativeExpect = (mdP - md) / shift;
    assertThat(mdAd.getDerivative(0)).isCloseTo(derivativeExpect, offset(EPS));
  }

  @Test
  public void convexityFromYieldUS() {
    double computed = PRICER.convexityFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldUSLastPeriod() {
    double computed = PRICER.convexityFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void macaulayDurationFromYieldUS() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_US, SETTLEMENT_US, YIELD_US);
    assertThat(duration).isCloseTo(4.6575232098896215, offset(TOL)); // 2.x.
  }

  @Test
  public void macaulayDurationFromYieldUSLastPeriod() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_US, SETTLEMENT_LAST_US, YIELD_US);
    assertThat(duration).isCloseTo(0.43478260869565216, offset(TOL)); // 2.x.
  }

  /* UK BUMP/DMO convention */
  private static final LocalDate START_UK = date(2002, 9, 7);
  private static final LocalDate END_UK = START_UK.plusYears(12);
  private static final PeriodicSchedule SCHEDULE_UK = PeriodicSchedule.of(START_UK, END_UK, Frequency.P6M,
      BusinessDayAdjustment.of(BusinessDayConventions.FOLLOWING, SAT_SUN),
      StubConvention.SHORT_INITIAL, false);
  private static final ResolvedFixedCouponBond PRODUCT_UK = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DayCounts.ACT_ACT_ICMA)
      .fixedRate(0.05)
      .legalEntityId(ISSUER_ID)
      .currency(Currency.GBP)
      .notional(100)
      .accrualSchedule(SCHEDULE_UK)
      .settlementDateOffset(DaysAdjustment.ofBusinessDays(1, SAT_SUN))
      .yieldConvention(FixedCouponBondYieldConvention.GB_BUMP_DMO)
      .exCouponPeriod(DaysAdjustment.ofCalendarDays(-7,
          BusinessDayAdjustment.of(BusinessDayConventions.PRECEDING, SAT_SUN)))
      .build()
      .resolve(REF_DATA);
  private static final LocalDate VALUATION_UK = date(2011, 9, 2);
  private static final LocalDate SETTLEMENT_UK = PRODUCT_UK.getSettlementDateOffset().adjust(VALUATION_UK, REF_DATA);
  private static final LocalDate VALUATION_LAST_UK = date(2014, 6, 3);
  private static final LocalDate SETTLEMENT_LAST_UK = PRODUCT_UK.getSettlementDateOffset().adjust(VALUATION_LAST_UK, REF_DATA);
  private static final double YIELD_UK = 0.04;

  @Test
  public void dirtyPriceFromYieldUK() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    assertThat(dirtyPrice).isCloseTo(1.0277859038905428, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_UK, SETTLEMENT_UK, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_UK, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldUKLastPeriod() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    assertThat(dirtyPrice).isCloseTo(1.0145736043763598, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_UK, SETTLEMENT_LAST_UK, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_UK, offset(TOL));
  }

  @Test
  public void modifiedDurationFromYieldUK() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldUKLastPeriod() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldUK() {
    double computed = PRICER.convexityFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldUKLastPeriod() {
    double computed = PRICER.convexityFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void macaulayDurationFromYieldUK() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_UK, SETTLEMENT_UK, YIELD_UK);
    assertThat(duration).isCloseTo(2.8312260658609163, offset(TOL)); // 2.x.
  }

  @Test
  public void macaulayDurationFromYieldUKLastPeriod() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_UK, SETTLEMENT_LAST_UK, YIELD_UK);
    assertThat(duration).isCloseTo(0.25815217391304346, offset(TOL)); // 2.x.
  }

  /* German bond convention */
  private static final LocalDate START_GER = date(2002, 9, 7);
  private static final LocalDate END_GER = START_GER.plusYears(12);
  private static final PeriodicSchedule SCHEDULE_GER = PeriodicSchedule.of(START_GER, END_GER, Frequency.P12M,
      BusinessDayAdjustment.of(BusinessDayConventions.FOLLOWING, SAT_SUN),
      StubConvention.SHORT_INITIAL, false);
  private static final ResolvedFixedCouponBond PRODUCT_GER = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DayCounts.ACT_ACT_ICMA)
      .fixedRate(0.05)
      .legalEntityId(ISSUER_ID)
      .currency(Currency.EUR)
      .notional(100)
      .accrualSchedule(SCHEDULE_GER)
      .settlementDateOffset(DaysAdjustment.ofBusinessDays(3, SAT_SUN))
      .yieldConvention(FixedCouponBondYieldConvention.DE_BONDS)
      .exCouponPeriod(DaysAdjustment.NONE)
      .build()
      .resolve(REF_DATA);
  private static final LocalDate VALUATION_GER = date(2011, 9, 2);
  private static final LocalDate SETTLEMENT_GER = PRODUCT_GER.getSettlementDateOffset().adjust(VALUATION_GER, REF_DATA);
  private static final LocalDate VALUATION_LAST_GER = date(2014, 6, 3);
  private static final LocalDate SETTLEMENT_LAST_GER = PRODUCT_GER.getSettlementDateOffset().adjust(VALUATION_LAST_GER, REF_DATA);
  private static final double YIELD_GER = 0.04;

  @Test
  public void dirtyPriceFromYieldGerman() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    assertThat(dirtyPrice).isCloseTo(1.027750910332271, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_GER, SETTLEMENT_GER, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_GER, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldGermanLastPeriod() {
    double dirtyPrice = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    assertThat(dirtyPrice).isCloseTo(1.039406595790844, offset(TOL)); // 2.x.
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_GER, SETTLEMENT_LAST_GER, dirtyPrice);
    assertThat(yield).isCloseTo(YIELD_GER, offset(TOL));
  }

  @Test
  public void modifiedDurationFromYieldGER() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldGERLastPeriod() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldGER() {
    double computed = PRICER.convexityFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldGERLastPeriod() {
    double computed = PRICER.convexityFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void macaulayDurationFromYieldGER() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_GER, SETTLEMENT_GER, YIELD_GER);
    assertThat(duration).isCloseTo(2.861462874541554, offset(TOL)); // 2.x.
  }

  @Test
  public void macaulayDurationFromYieldGERLastPeriod() {
    double duration = PRICER.macaulayDurationFromYield(PRODUCT_GER, SETTLEMENT_LAST_GER, YIELD_GER);
    assertThat(duration).isCloseTo(0.26231286613148186, offset(TOL)); // 2.x.
  }

  /* Japan simple convention */
  private static final LocalDate START_JP = date(2015, 9, 20);
  private static final LocalDate END_JP = START_JP.plusYears(10);
  private static final PeriodicSchedule SCHEDULE_JP = PeriodicSchedule.of(START_JP, END_JP, Frequency.P6M,
      BusinessDayAdjustment.of(BusinessDayConventions.FOLLOWING, JPTO),
      StubConvention.SHORT_INITIAL, false);
  private static final double RATE_JP = 0.004;
  private static final ResolvedFixedCouponBond PRODUCT_JP = FixedCouponBond.builder()
      .securityId(SECURITY_ID)
      .dayCount(DayCounts.NL_365)
      .fixedRate(RATE_JP)
      .legalEntityId(ISSUER_ID)
      .currency(Currency.JPY)
      .notional(100)
      .accrualSchedule(SCHEDULE_JP)
      .settlementDateOffset(DaysAdjustment.ofBusinessDays(3, JPTO))
      .yieldConvention(FixedCouponBondYieldConvention.JP_SIMPLE)
      .exCouponPeriod(DaysAdjustment.NONE)
      .build()
      .resolve(REF_DATA);
  private static final LocalDate VALUATION_JP = date(2015, 9, 24);
  private static final LocalDate SETTLEMENT_JP = PRODUCT_JP.getSettlementDateOffset().adjust(VALUATION_JP, REF_DATA);
  private static final LocalDate VALUATION_LAST_JP = date(2025, 6, 3);
  private static final LocalDate SETTLEMENT_LAST_JP = PRODUCT_JP.getSettlementDateOffset().adjust(VALUATION_LAST_JP, REF_DATA);
  private static final LocalDate VALUATION_ENDED_JP = date(2026, 8, 3);
  private static final LocalDate SETTLEMENT_ENDED_JP = PRODUCT_JP.getSettlementDateOffset().adjust(VALUATION_ENDED_JP, REF_DATA);
  private static final double YIELD_JP = 0.00321;

  @Test
  public void dirtyPriceFromYieldJP() {
    double computed = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    double maturity = DayCounts.NL_365.relativeYearFraction(SETTLEMENT_JP, END_JP);
    double expected = PRICER.dirtyPriceFromCleanPrice(
        PRODUCT_JP, SETTLEMENT_JP, (1d + RATE_JP * maturity) / (1d + YIELD_JP * maturity));
    assertThat(computed).isCloseTo(expected, offset(TOL));
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_JP, SETTLEMENT_JP, computed);
    assertThat(yield).isCloseTo(YIELD_JP, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldJPLastPeriod() {
    double computed = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP);
    double maturity = DayCounts.NL_365.relativeYearFraction(SETTLEMENT_LAST_JP, END_JP);
    double expected = PRICER.dirtyPriceFromCleanPrice(
        PRODUCT_JP, SETTLEMENT_LAST_JP, (1d + RATE_JP * maturity) / (1d + YIELD_JP * maturity));
    assertThat(computed).isCloseTo(expected, offset(TOL));
    double yield = PRICER.yieldFromDirtyPrice(PRODUCT_JP, SETTLEMENT_LAST_JP, computed);
    assertThat(yield).isCloseTo(YIELD_JP, offset(TOL));
  }

  @Test
  public void dirtyPriceFromYieldJPEnded() {
    double computed = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_ENDED_JP, YIELD_JP);
    assertThat(computed).isCloseTo(0d, offset(TOL));
  }

  @Test
  public void modifiedDurationFromYielddJP() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYielddJP_AD() {
    double shift = 1.0E-7;
    double md = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    ValueDerivatives mdAd = PRICER.modifiedDurationFromYieldAd(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    assertThat(mdAd.getValue()).isCloseTo(md, offset(EPS));
    double mdP = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP + shift);
    double mdM = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP - shift);
    double derivativeExpect = (mdP - mdM) / (2 * shift);
    assertThat(mdAd.getDerivative(0)).isCloseTo(derivativeExpect, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYieldJPLastPeriod() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP);
    double price = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP);
    double priceUp = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP + EPS);
    double priceDw = PRICER.dirtyPriceFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP - EPS);
    double expected = 0.5 * (priceDw - priceUp) / price / EPS;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void modifiedDurationFromYielddJPEnded() {
    double computed = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_ENDED_JP, YIELD_JP);
    assertThat(computed).isCloseTo(0d, offset(EPS));
  }

  @Test
  public void convexityFromYieldJP() {
    double computed = PRICER.convexityFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldJPLastPeriod() {
    double computed = PRICER.convexityFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP);
    double duration = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP);
    double durationUp = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP + EPS);
    double durationDw = PRICER.modifiedDurationFromYield(PRODUCT_JP, SETTLEMENT_LAST_JP, YIELD_JP - EPS);
    double expected = 0.5 * (durationDw - durationUp) / EPS + duration * duration;
    assertThat(computed).isCloseTo(expected, offset(EPS));
  }

  @Test
  public void convexityFromYieldJPEnded() {
    double computed = PRICER.convexityFromYield(PRODUCT_JP, SETTLEMENT_ENDED_JP, YIELD_JP);
    assertThat(computed).isCloseTo(0d, offset(EPS));
  }

  @Test
  public void macaulayDurationFromYieldYieldJP() {
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> PRICER.macaulayDurationFromYield(PRODUCT_JP, SETTLEMENT_JP, YIELD_JP))
        .withMessage("The convention JP_SIMPLE is not supported.");
  }

  @Test
  public void zSpreadFromCurvesAndPV_acrossExDivDate() {
    LocalDate threeDayBeforeExDiv = LocalDate.of(2019, 2, 24);
    LocalDate twoDayBeforeExDiv = LocalDate.of(2019, 2, 25);
    LocalDate oneDayBeforeExDiv = LocalDate.of(2019, 2, 26);
    LocalDate exDiv = LocalDate.of(2019, 2, 27);
    LocalDate exDivP1 = LocalDate.of(2019, 2, 28);
    ResolvedFixedCouponBond bond = FixedCouponBond.builder()
        .securityId(SECURITY_ID)
        .dayCount(DayCounts.ACT_ACT_ICMA)
        .fixedRate(0.0375)
        .legalEntityId(ISSUER_ID)
        .currency(GBP)
        .notional(NOTIONAL)
        .accrualSchedule(PeriodicSchedule.of(LocalDate.of(2010, 9, 7), LocalDate.of(2020, 9, 7), Frequency.P6M,
            BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, HolidayCalendarIds.GBLO),
            StubConvention.SMART_INITIAL, false))
        .settlementDateOffset(DaysAdjustment.ofBusinessDays(1, HolidayCalendarIds.GBLO))
        .yieldConvention(FixedCouponBondYieldConvention.GB_BUMP_DMO)
        .exCouponPeriod(DaysAdjustment.ofCalendarDays(-8,
            BusinessDayAdjustment.of(BusinessDayConventions.MODIFIED_FOLLOWING, HolidayCalendarIds.GBLO)))
        .build()
        .resolve(REF_DATA);
    List<LocalDate> dates = ImmutableList.of(threeDayBeforeExDiv, twoDayBeforeExDiv, oneDayBeforeExDiv, exDiv, exDivP1);

    for (LocalDate date : dates) {
      LegalEntityDiscountingProvider dateProvider = ImmutableLegalEntityDiscountingProvider.builder()
          .issuerCurves(
              ImmutableMap.of(Pair.of(GROUP_ISSUER, GBP), ZeroRateDiscountFactors.of(GBP, date, CURVE_ISSUER)))
          .issuerCurveGroups(ImmutableMap.of(ISSUER_ID, GROUP_ISSUER))
          .repoCurves(ImmutableMap.of(Pair.of(GROUP_REPO, GBP), ZeroRateDiscountFactors.of(GBP, date, CURVE_REPO)))
          .repoCurveSecurityGroups(ImmutableMap.of(SECURITY_ID, GROUP_REPO))
          .valuationDate(date)
          .build();
      LocalDate settlement = DaysAdjustment.ofBusinessDays(1, HolidayCalendarIds.GBLO).adjust(date, REF_DATA);
      double dirtyPrice = PRICER.dirtyPriceFromCleanPrice(bond, settlement, 1.04494d);

      double zSpread = PRICER.zSpreadFromCurvesAndDirtyPrice(bond, dateProvider, REF_DATA, dirtyPrice, PERIODIC, 2);
      double dirtyPriceZ = PRICER.dirtyPriceFromCurvesWithZSpread(bond, dateProvider, REF_DATA, zSpread, PERIODIC, 2);
      assertThat(dirtyPriceZ).isCloseTo(dirtyPrice, offset(TOL));
      assertThat(zSpread).as(date.format(DateTimeFormatter.ISO_DATE)).isCloseTo(-.025, offset(5e-3));

      double yield = PRICER.yieldFromDirtyPrice(bond, settlement, dirtyPrice);
      double dirtyPriceY = PRICER.dirtyPriceFromYield(bond, settlement, yield);
      assertThat(dirtyPriceY).isCloseTo(dirtyPrice, offset(TOL));
      assertThat(yield).as(date.format(DateTimeFormatter.ISO_DATE)).isCloseTo(.007, offset(1e-3));
    }
  }

}
