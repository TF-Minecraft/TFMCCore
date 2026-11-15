package net.tfminecraft.tfmccore.guide;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The /guide topics and messages, read from guide.yml. Each topic links one page or section of
 * the Gameplay Guide on the website; staff add topics there without a code change.
 */
public final class GuideConfig {

    /** One guide topic: what players type after /guide, and the page it links. */
    public record Topic(String id, String title, String description, String url) {}

    static final String DEFAULT_BASE_URL = "https://www.tfminecraft.net/wiki";

    private static final Logger LOGGER = Logger.getLogger("TFMCCore");
    // Topic ids are a single Brigadier word, matched without case
    private static final Pattern TOPIC_ID = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)https?://.+");
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "header", "<gold><b>Gameplay Guide</b></gold> <dark_gray>|</dark_gray> <gray>Click a topic to open its page on the website.",
            "topic", "<dark_gray> » </dark_gray><open><yellow><title></yellow></open> <suggest><dark_gray>[/guide <id>]</dark_gray></suggest>",
            "topic-hover", "<gray><description>",
            "suggest-hover", "<gray>Click to type <white>/guide <id></white>",
            "link", "<gold>Gameplay Guide: </gold><open><yellow><u><title></u></yellow></open> <gray><description>",
            "link-hover", "<gray>Open <white><url></white>",
            "unknown", "<red>There is no guide topic called <yellow><topic></yellow>.",
            "empty", "<red>No guide topics are set up yet.");

    private volatile YamlConfiguration config = new YamlConfiguration();
    private volatile Map<String, Topic> topics = Map.of();

    public boolean load(File file) {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (Exception e) {
            LOGGER.warning("Failed to load " + file.getName() + ": " + e.getMessage());
            return false;
        }
        apply(loaded);
        return true;
    }

    public void loadFromString(String yaml) throws InvalidConfigurationException {
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(yaml);
        apply(loaded);
    }

    private void apply(YamlConfiguration loaded) {
        topics = parseTopics(loaded);
        config = loaded;
    }

    /** Every usable topic, in file order. */
    public Collection<Topic> topics() {
        return topics.values();
    }

    /** The topic a player typed, ignoring case. */
    public Optional<Topic> topic(String id) {
        return Optional.ofNullable(topics.get(id.toLowerCase(Locale.ROOT)));
    }

    /** A message from messages.&lt;key&gt;, one string or a list of lines; the built-in text when missing. */
    public List<String> lines(String key) {
        String path = "messages." + key;
        if (config.isList(path)) {
            return config.getStringList(path);
        }
        String value = config.getString(path);
        return List.of(value == null ? DEFAULT_MESSAGES.getOrDefault(key, "") : value);
    }

    /** A single-line message (the topic and link formats and hovers). */
    public String line(String key) {
        return String.join("\n", lines(key));
    }

    private static Map<String, Topic> parseTopics(YamlConfiguration loaded) {
        String base = loaded.getString("base-url", "");
        base = base.isBlank() ? DEFAULT_BASE_URL : base.strip().replaceAll("/+$", "");
        Map<String, Topic> parsed = new LinkedHashMap<>();
        ConfigurationSection section = loaded.getConfigurationSection("topics");
        if (section == null) {
            return parsed;
        }
        for (String key : section.getKeys(false)) {
            String id = key.toLowerCase(Locale.ROOT);
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (!TOPIC_ID.matcher(id).matches() || entry == null) {
                LOGGER.warning("Skipping guide topic '" + key + "': ids are lowercase letters, digits, - and _, with path, title and description below");
                continue;
            }
            if (parsed.containsKey(id)) {
                LOGGER.warning("Skipping guide topic '" + key + "': the id " + id + " is already used");
                continue;
            }
            String url = url(base, entry.getString("path", "").strip());
            if (url == null) {
                LOGGER.warning("Skipping guide topic '" + key + "': its link is not a valid http(s) address");
                continue;
            }
            String title = entry.getString("title", "");
            parsed.put(id, new Topic(id, title.isBlank() ? key : title, entry.getString("description", ""), url));
        }
        return parsed;
    }

    /** base-url followed by the path, or the path itself when it is a full address; null if not a web address. */
    static String url(String base, String path) {
        String url;
        if (ABSOLUTE.matcher(path).matches()) {
            url = path;
        } else if (path.isEmpty() || path.startsWith("/") || path.startsWith("#") || path.startsWith("?")) {
            url = base + path;
        } else {
            url = base + "/" + path;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            boolean web = scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
            return web && uri.getHost() != null ? url : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
