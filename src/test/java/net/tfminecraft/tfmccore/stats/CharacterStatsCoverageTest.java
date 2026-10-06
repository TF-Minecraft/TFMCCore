package net.tfminecraft.tfmccore.stats;

import static org.mockito.Mockito.*;

import java.util.UUID;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterClassChangeEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterCreatedEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterRaceChangeEvent;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatListener;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatMain;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class CharacterStatsCoverageTest {
  private final UUID owner = UUID.randomUUID();
  private final StatManager manager = mock(StatManager.class);
  private final RpCharactersStatListener listener =
      new RpCharactersStatListener(new RpCharactersStatMain());
  private MockedStatic<StatManager> managers;

  @BeforeEach
  void setup() {
    managers = mockStatic(StatManager.class);
    managers.when(StatManager::isInitialized).thenReturn(true);
    managers.when(StatManager::getInstance).thenReturn(manager);
  }

  @AfterEach
  void close() {
    managers.close();
  }

  @Test
  void disabledStatsIgnoreEveryCharacterEvent() {
    managers.when(StatManager::isInitialized).thenReturn(false);
    var created = mock(CharacterCreatedEvent.class);
    var classes = mock(CharacterClassChangeEvent.class);
    var races = mock(CharacterRaceChangeEvent.class);
    var killed = mock(CharacterPermakillEvent.class);
    var chat = mock(CharacterChatEvent.class);
    listener.onCharacterCreated(created);
    listener.onCharacterClassChange(classes);
    listener.onCharacterRaceChange(races);
    listener.onCharacterPermakill(killed);
    listener.onCharacterChat(chat);
    verifyNoInteractions(created, classes, races, killed, chat, manager);
  }

  @Test
  void creationRequiresAnOwnerAndCountsOnlyAvailableClassAndRace() {
    var event = mock(CharacterCreatedEvent.class, RETURNS_DEEP_STUBS);
    when(event.getOwnerUuid()).thenReturn(null);
    listener.onCharacterCreated(event);
    verifyNoInteractions(manager);
    when(event.getOwnerUuid()).thenReturn(owner);
    var character = event.getCharacter();
    when(event.getCharacter()).thenReturn(null);
    listener.onCharacterCreated(event);
    when(event.getCharacter()).thenReturn(character);
    when(character.getMMOClass()).thenReturn(" ");
    when(character.getRace()).thenReturn(null);
    listener.onCharacterCreated(event);
    when(character.getMMOClass()).thenReturn("PALADIN");
    var race = mock(net.tfminecraft.rpcharacters.objects.races.Race.class);
    when(race.getId()).thenReturn("ELF");
    when(character.getRace()).thenReturn(race);
    listener.onCharacterCreated(event);
    verify(manager, times(3)).increment(owner, "rpcharacters", "characters_created", 1L);
    verify(manager).increment(owner, "rpcharacters", "class_paladin", 1L);
    verify(manager).increment(owner, "rpcharacters", "race_elf", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void classAndRaceChangesAdjustTheOldAndNewSpreadsIndependently() {
    var classes = mock(CharacterClassChangeEvent.class);
    var races = mock(CharacterRaceChangeEvent.class);
    listener.onCharacterClassChange(classes);
    listener.onCharacterRaceChange(races);
    verifyNoInteractions(manager);
    when(classes.getOwnerUuid()).thenReturn(owner);
    when(races.getOwnerUuid()).thenReturn(owner);
    listener.onCharacterClassChange(classes);
    listener.onCharacterRaceChange(races);
    when(classes.getOldClassId()).thenReturn(" ");
    when(classes.getNewClassId()).thenReturn(" ");
    when(races.getOldRaceId()).thenReturn(" ");
    when(races.getNewRaceId()).thenReturn(" ");
    listener.onCharacterClassChange(classes);
    listener.onCharacterRaceChange(races);
    verifyNoInteractions(manager);
    when(classes.getOldClassId()).thenReturn("PALADIN");
    when(classes.getNewClassId()).thenReturn("MAGE");
    when(races.getOldRaceId()).thenReturn("ELF");
    when(races.getNewRaceId()).thenReturn("HUMAN");
    listener.onCharacterClassChange(classes);
    listener.onCharacterRaceChange(races);
    verify(manager).decrement(owner, "rpcharacters", "class_paladin", 1L);
    verify(manager).increment(owner, "rpcharacters", "class_mage", 1L);
    verify(manager).decrement(owner, "rpcharacters", "race_elf", 1L);
    verify(manager).increment(owner, "rpcharacters", "race_human", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void permakillRemovesVictimSpreadsButMenuDeletionNeverCreditsAKiller() {
    var event = mock(CharacterPermakillEvent.class, RETURNS_DEEP_STUBS);
    var character = event.getCharacter();
    when(event.getCharacter()).thenReturn(null);
    when(event.getPlayer()).thenReturn(null);
    when(event.getKiller()).thenReturn(null);
    listener.onCharacterPermakill(event);
    when(event.getCharacter()).thenReturn(character);
    listener.onCharacterPermakill(event);
    Player victim = mock(Player.class);
    when(victim.getUniqueId()).thenReturn(owner);
    when(event.getPlayer()).thenReturn(victim);
    when(character.getMMOClass()).thenReturn(null);
    when(character.getRace()).thenReturn(null);
    listener.onCharacterPermakill(event);
    verifyNoInteractions(manager);
    when(character.getMMOClass()).thenReturn("MAGE");
    var race = mock(net.tfminecraft.rpcharacters.objects.races.Race.class);
    when(race.getId()).thenReturn("HUMAN");
    when(character.getRace()).thenReturn(race);
    Player killer = mock(Player.class);
    UUID killerId = UUID.randomUUID();
    when(killer.getUniqueId()).thenReturn(killerId);
    when(event.getKiller()).thenReturn(killer);
    when(event.getCause()).thenReturn(PermakillCause.CHARACTER_MENU);
    listener.onCharacterPermakill(event);
    verify(manager).decrement(owner, "rpcharacters", "class_mage", 1L);
    verify(manager).decrement(owner, "rpcharacters", "race_human", 1L);
    verify(manager, never()).increment(eq(killerId), anyString(), anyString(), anyLong());
    when(event.getCause())
        .thenReturn(
            java.util.Arrays.stream(PermakillCause.values())
                .filter(c -> c != PermakillCause.CHARACTER_MENU)
                .findFirst()
                .orElseThrow());
    listener.onCharacterPermakill(event);
    verify(manager, times(2)).decrement(owner, "rpcharacters", "class_mage", 1L);
    verify(manager, times(2)).decrement(owner, "rpcharacters", "race_human", 1L);
    verify(manager).increment(killerId, "rpcharacters", "characters_killed", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void chatCountsOnlyNamedChannelsWithASender() {
    var event = mock(CharacterChatEvent.class);
    listener.onCharacterChat(event);
    when(event.getChannel()).thenReturn(" ");
    listener.onCharacterChat(event);
    when(event.getChannel()).thenReturn("WHISPER");
    listener.onCharacterChat(event);
    verifyNoInteractions(manager);
    Player sender = mock(Player.class);
    when(sender.getUniqueId()).thenReturn(owner);
    when(event.getSender()).thenReturn(sender);
    listener.onCharacterChat(event);
    verify(manager).increment(owner, "rpcharacters", "messages_whisper", 1L);
    verifyNoMoreInteractions(manager);
  }
}
