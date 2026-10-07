package net.tfminecraft.tfmccore.testsupport;

import static org.mockito.Mockito.*;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;

/**
 * Stands in for the server's registries, which Paper finds through {@link java.util.ServiceLoader}.
 * Without it the first {@code Sound} constant a test touches fails to initialise, and every later
 * test in the same JVM gets {@code NoClassDefFoundError}. Sound, enchantment, potion and damage
 * identifiers resolve; other registries remain unavailable without a server.
 */
public final class TestRegistryAccess implements RegistryAccess {

  private static final Map<NamespacedKey, Sound> SOUNDS = new ConcurrentHashMap<>();
  private static final Map<NamespacedKey, Enchantment> ENCHANTMENTS = new ConcurrentHashMap<>();
  private static final Map<NamespacedKey, PotionEffectType> EFFECTS = new ConcurrentHashMap<>();
  private static final Map<NamespacedKey, DamageType> DAMAGE_TYPES = new ConcurrentHashMap<>();

  @Override
  @Deprecated
  public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
    return registry(
        Sound.class.equals(type),
        Enchantment.class.equals(type),
        PotionEffectType.class.equals(type),
        DamageType.class.equals(type));
  }

  @Override
  public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
    return registry(
        RegistryKey.SOUND_EVENT.equals(key),
        RegistryKey.ENCHANTMENT.equals(key),
        RegistryKey.MOB_EFFECT.equals(key),
        RegistryKey.DAMAGE_TYPE.equals(key));
  }

  @SuppressWarnings("unchecked")
  private static <T extends Keyed> Registry<T> registry(
      boolean sounds, boolean enchantments, boolean effects, boolean damage) {
    return (Registry<T>)
        Proxy.newProxyInstance(
            Registry.class.getClassLoader(),
            new Class<?>[] {Registry.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "equals" -> self == args[0];
                  case "hashCode" -> System.identityHashCode(self);
                  case "toString" -> sounds ? "TestRegistry[sounds]" : "TestRegistry[unavailable]";
                  case "iterator" -> {
                    if (!sounds)
                      throw new UnsupportedOperationException(
                          "Only sound enumeration is supported");
                    yield SOUNDS.values().iterator();
                  }
                  case "getKey" -> ((Keyed) args[0]).getKey();
                  case "get", "getOrThrow" -> {
                    NamespacedKey key = (NamespacedKey) args[0];
                    boolean create = method.getName().equals("getOrThrow");
                    if (sounds)
                      yield create
                          ? SOUNDS.computeIfAbsent(key, TestRegistryAccess::sound)
                          : SOUNDS.get(key);
                    if (enchantments)
                      yield create
                          ? ENCHANTMENTS.computeIfAbsent(key, TestRegistryAccess::enchantment)
                          : ENCHANTMENTS.get(key);
                    if (effects)
                      yield create
                          ? EFFECTS.computeIfAbsent(key, k -> identifier(PotionEffectType.class, k))
                          : EFFECTS.get(key);
                    if (damage)
                      yield create
                          ? DAMAGE_TYPES.computeIfAbsent(key, k -> identifier(DamageType.class, k))
                          : DAMAGE_TYPES.get(key);
                    throw new IllegalStateException(
                        "This registry is unavailable without a server");
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static <T> T identifier(Class<T> type, NamespacedKey key) {
    return mock(
        type,
        invocation ->
            switch (invocation.getMethod().getName()) {
              case "getKey", "key" -> key;
              case "getName" -> key.getKey();
              default -> RETURNS_DEFAULTS.answer(invocation);
            });
  }

  private static Enchantment enchantment(NamespacedKey key) {
    return mock(
        Enchantment.class,
        invocation ->
            switch (invocation.getMethod().getName()) {
              case "getKey", "key" -> key;
              case "getName" -> key.getKey();
              default -> RETURNS_DEFAULTS.answer(invocation);
            });
  }

  private static Sound sound(NamespacedKey key) {
    return (Sound)
        Proxy.newProxyInstance(
            Sound.class.getClassLoader(),
            new Class<?>[] {Sound.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "equals" -> self == args[0];
                  case "hashCode" -> key.hashCode();
                  case "toString" -> "Sound[" + key + "]";
                  case "getKey", "key" -> key;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
