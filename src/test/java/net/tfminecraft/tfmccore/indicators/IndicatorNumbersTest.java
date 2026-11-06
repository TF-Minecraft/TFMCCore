package net.tfminecraft.tfmccore.indicators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IndicatorNumbersTest {

  private static String doubled(String line) {
    return IndicatorNumbers.rewrite(line, value -> value * 2, value -> "<" + value + ">");
  }

  @Test
  void rewritesTheValueAfterMythicLibsIconsAndColours() {
    assertEquals("&c🗡&4✘ &f<12.8>", doubled("&c🗡&4✘ &f6.4"));
    assertEquals("§c✘ §f<24.0>", doubled("§c✘ §f12"));
    assertEquals("&c&l🗡&4&l✘ &f<19.0>&7", doubled("&c&l🗡&4&l✘ &f9.5&7"));
  }

  @Test
  void readsACommaDecimalSeparator() {
    assertEquals("&f<12.8>", doubled("&f6,4"));
  }

  @Test
  void skipsHexColours() {
    assertEquals("&#ff0000✘ &#123456 <7.0>", doubled("&#ff0000✘ &#123456 3.5"));
    assertEquals("<#a1b2c3>✘ <7.0>", doubled("<#a1b2c3>✘ 3.5"));
  }

  @Test
  void rewritesEveryNumberOfAJoinedLine() {
    assertEquals(2, IndicatorNumbers.count("&c🗡 &f6 &9✩ &f2"));
    assertEquals("&c🗡 &f<12.0> &9✩ &f<4.0>", doubled("&c🗡 &f6 &9✩ &f2"));
  }

  @Test
  void aLineWithoutANumberIsUnchanged() {
    assertEquals(0, IndicatorNumbers.count("&4✘ &fᜆᜊᜄ"));
    assertEquals("&4✘ &fᜆᜊᜄ", doubled("&4✘ &fᜆᜊᜄ"));
    assertEquals("", doubled(""));
  }

  @Test
  void digitsFarFromAHashAreNumbers() {
    assertEquals("abcdef<14.0>", doubled("abcdef7"));
  }
}
