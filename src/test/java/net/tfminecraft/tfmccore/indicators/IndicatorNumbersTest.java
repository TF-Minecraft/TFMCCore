package net.tfminecraft.tfmccore.indicators;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

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
  void aColourCodeRightBeforeTheValueIsNotPartOfIt() {
    assertEquals(List.of(6.4), IndicatorNumbers.values("&46.4"));
    assertEquals(List.of(6.4), IndicatorNumbers.values("&#ff00006.4"));
    assertEquals(List.of(6.4), IndicatorNumbers.values("#1234566.4"));
  }

  @Test
  void readsACommaDecimalSeparator() {
    assertEquals("&f<12.8>", doubled("&f6,4"));
  }

  @Test
  void skipsHexColoursAndMiniMessageTags() {
    assertEquals("&#ff0000✘ &#123456 <7.0>", doubled("&#ff0000✘ &#123456 3.5"));
    assertEquals("<#a1b2c3>✘ <7.0>", doubled("<#a1b2c3>✘ 3.5"));
    assertEquals(List.of(3.0), IndicatorNumbers.values("<shadow:#000000:0.5>✘ §x§f§f§0§0§0§03"));
  }

  @Test
  void rewritesEveryNumberOfAJoinedLine() {
    assertEquals(2, IndicatorNumbers.count("&c🗡 &f6 &9✩ &f2"));
    assertEquals(List.of(6.0, 2.0), IndicatorNumbers.values("&c🗡 &f6 &9✩ &f2"));
    assertEquals("&c🗡 &f<12.0> &9✩ &f<4.0>", doubled("&c🗡 &f6 &9✩ &f2"));
  }

  @Test
  void aLineWithoutANumberIsUnchanged() {
    assertEquals(0, IndicatorNumbers.count("&4✘ &fᜆᜊᜄ"));
    assertEquals("&4✘ &fᜆᜊᜄ", doubled("&4✘ &fᜆᜊᜄ"));
    assertEquals("", doubled(""));
  }

  @Test
  void plainNumbersAreNumbers() {
    assertEquals("abcdef<14.0>", doubled("abcdef7"));
  }
}
