package net.tfminecraft.tfmccore.health;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ExactMaxHealthTest {

  /**
   * MythicLib 1.7.1 Resources.setHealth at full health, as Paper stores it: health is a float, the
   * max is the attribute's double. True when a regen tick finds nothing to change.
   */
  private static boolean regenStopsAtFull(double max) {
    double stored = (float) max;
    double next = Math.max(0.0, Math.min(stored + 0.03, max));
    return stored == next;
  }

  @Test
  void fractionalLiveTotalsAreNotFloatExactAndNeverCountAsFull() {
    // Bard level 2 (20 + 0.3), constitution and nutrition points, AC 2-decimal armour stats
    for (double max : new double[] {23.6, 20.3, 26.35, 22.7, 21.68, 24.16}) {
      assertFalse(ExactMaxHealth.isFloatExact(max), "" + max);
      assertFalse(regenStopsAtFull(max), "" + max);
    }
  }

  @Test
  void halvesQuartersAndTheCapsAreFloatExact() {
    for (double max : new double[] {1, 20, 20.5, 23.25, 26.125, 1024, 0x1p-10}) {
      assertTrue(ExactMaxHealth.isFloatExact(max), "" + max);
      assertTrue(regenStopsAtFull(max), "" + max);
    }
    assertFalse(ExactMaxHealth.isFloatExact(Double.NaN));
  }

  @Test
  void roundsDownToTheStepAndTheResultStopsTheRegenLoop() {
    assertEquals(23.599609375, ExactMaxHealth.round(23.6));
    assertEquals(20.0, ExactMaxHealth.round(20.0));
    assertEquals(23.5, ExactMaxHealth.round(23.5));
    // 0.1 + 0.35 + 0.05 is 0.49999999999999994 in double; it still counts as 0.5
    assertEquals(0.5, ExactMaxHealth.round(0.1 + 0.35 + 0.05));
    Random random = new Random(42);
    for (int i = 0; i < 100_000; i++) {
      double max = 1 + random.nextDouble() * 1023;
      double rounded = ExactMaxHealth.round(max);
      assertTrue(ExactMaxHealth.isFloatExact(rounded), "" + max);
      assertTrue(regenStopsAtFull(rounded), "" + max);
      assertTrue(rounded <= max + 1e-9 && max - rounded < ExactMaxHealth.STEP, "" + max);
    }
  }

  @Test
  void modifierAmountMakesTheVanillaSumExactInAnyOrder() {
    Random random = new Random(7);
    for (int i = 0; i < 20_000; i++) {
      // MythicLib's total plus a mercenary bonus (half hearts) and Health Boost (4 per level)
      double others1 = 0.5 * random.nextInt(11);
      double others2 = 4.0 * random.nextInt(3);
      double total = 20 + others1 + others2 + random.nextDouble() * 40 - 5;
      double amount = ExactMaxHealth.modifierAmount(total, 20, others1 + others2);
      double target = ExactMaxHealth.round(total);
      for (List<Double> order : orders(amount, others1, others2)) {
        double sum = 20;
        for (double part : order) sum += part;
        assertEquals(target, sum, "order " + order);
      }
      assertTrue(ExactMaxHealth.isFloatExact(target));
    }
  }

  private static List<List<Double>> orders(double a, double b, double c) {
    List<List<Double>> orders = new ArrayList<>();
    orders.add(List.of(a, b, c));
    orders.add(List.of(a, c, b));
    orders.add(List.of(b, a, c));
    orders.add(List.of(b, c, a));
    orders.add(List.of(c, a, b));
    orders.add(List.of(c, b, a));
    return orders;
  }
}
