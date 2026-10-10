package net.tfminecraft.tfmccore.stones;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoneColoursTest {

  @ParameterizedTest
  @CsvSource({
    "black, &0",
    "GOLD, &6",
    "dark blue, &1",
    "light_purple, &d",
    "white, &f",
    "#ff8800, &#ff8800",
    "FF8800, &#ff8800",
    "none, ''",
    "Default, ''"
  })
  void namesAndHexMapToLegacyCodes(String input, String code) {
    assertEquals(code, StoneColours.code(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sparkly", "#ff88", "#gggggg", "&a", ""})
  void anythingElseIsNotAColour(String input) {
    assertNull(StoneColours.code(input));
  }

  @Test
  void paletteOffersEveryColourAndNoneAsClickableButtons() {
    List<Component> buttons =
        StoneColours.palette().children().stream()
            .filter(c -> c.clickEvent() != null)
            .toList();
    assertEquals(17, buttons.size());

    Component gold = buttons.get(6);
    assertEquals("[Gold]", ((TextComponent) gold).content());
    assertEquals(NamedTextColor.GOLD, gold.color());
    assertEquals(ClickEvent.Action.RUN_COMMAND, gold.clickEvent().action());
    assertEquals("/tcore stones colour gold", ((ClickEvent.Payload.Text) gold.clickEvent().payload()).value());
    assertEquals("[Dark Blue]", ((TextComponent) buttons.get(1)).content());
    assertEquals("[None]", ((TextComponent) buttons.getLast()).content());
    assertNotNull(gold.hoverEvent());
  }
}
