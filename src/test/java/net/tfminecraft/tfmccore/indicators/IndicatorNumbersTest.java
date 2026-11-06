package net.tfminecraft.tfmccore.indicators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IndicatorNumbersTest {

  @Test
  void readsTheValueAfterMythicLibsIconsAndColours() {
    assertEquals(6.4, IndicatorNumbers.value("&c🗡&4✘ &f6.4"));
    assertEquals(12.0, IndicatorNumbers.value("§c✘ §f12"));
    assertEquals(9.5, IndicatorNumbers.value("&c&l🗡&4&l✘ &f9.5&7"));
  }

  @Test
  void readsACommaDecimalSeparator() {
    assertEquals(6.4, IndicatorNumbers.value("&f6,4"));
  }

  @Test
  void skipsHexColours() {
    assertEquals(3.5, IndicatorNumbers.value("&#ff0000✘ &#123456 3.5"));
    assertEquals(3.5, IndicatorNumbers.value("<#a1b2c3>✘ 3.5"));
  }

  @Test
  void replacesOnlyTheValue() {
    assertEquals("&c🗡&4✘ &f3.7", IndicatorNumbers.replace("&c🗡&4✘ &f6.4", "3.7"));
    assertEquals("&4✘ &f2&7", IndicatorNumbers.replace("&4✘ &f10&7", "2"));
  }

  @Test
  void aLineWithoutANumberIsLeftToTheCaller() {
    assertNull(IndicatorNumbers.value("&4✘ &fᜆᜊᜄ"));
    assertNull(IndicatorNumbers.replace("&4✘", "1"));
    assertNull(IndicatorNumbers.value(""));
  }

  @Test
  void digitsFarFromAHashAreNumbers() {
    assertEquals(7.0, IndicatorNumbers.value("abcdef7"));
  }
}
