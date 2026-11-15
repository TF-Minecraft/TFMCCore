package net.tfminecraft.tfmccore.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuideConfigTest {

    private static final String WIKI = "https://www.tfminecraft.net/wiki";

    @TempDir
    Path directory;

    @Test
    void bundledTopicsLinkPlayerFacingWikiPagesInFileOrder() throws Exception {
        GuideConfig config = bundled();

        List<String> ids = config.topics().stream().map(GuideConfig.Topic::id).toList();
        assertEquals(List.of("overview", "start", "characters", "classes", "roleplay", "factions", "government",
                "guilds", "war", "money", "crafting", "gems", "woodworking", "materials", "cooking", "farming",
                "fishing", "animals", "brewing", "nodes", "magic", "research", "archaeology", "vehicles", "guns",
                "thievery", "locks", "medicine", "account", "commands"), ids);
        assertEquals(WIKI, config.topic("overview").orElseThrow().url());
        assertEquals(WIKI + "/factions#government", config.topic("government").orElseThrow().url());
        assertEquals(WIKI + "/vehicles", config.topic("vehicles").orElseThrow().url());
        assertEquals(WIKI + "/animal-husbandry", config.topic("animals").orElseThrow().url());
        Set<String> urls = new HashSet<>();
        for (GuideConfig.Topic topic : config.topics()) {
            assertTrue(topic.url().startsWith(WIKI), topic.id());
            assertTrue(urls.add(topic.url()), "One topic per page or section: " + topic.id());
            assertFalse(topic.title().isBlank(), topic.id());
            assertFalse(topic.description().isBlank(), topic.id());
            // House style of the guide: no em dashes
            assertFalse(topic.description().contains("—") || topic.title().contains("—"), topic.id());
        }
        assertEquals("Faction Government", config.topic("Government").orElseThrow().title());
    }

    @Test
    void topicsAreCheckedAndBadOnesSkipped() throws Exception {
        GuideConfig config = new GuideConfig();
        config.loadFromString("""
                base-url: "https://example.org/guide//"
                topics:
                  Maps:
                    path: "maps"
                  maps:
                    path: "/maps-again"
                  bad id:
                    path: "/bad"
                  plain: "not a section"
                  spaced:
                    path: "/has a space"
                  query:
                    path: "?tab=one"
                    title: " "
                    description: "Query"
                  outside:
                    path: "HTTPS://other.example.org/page"
                  nohost:
                    path: "https:///nowhere"
                """);

        assertEquals(List.of("maps", "query", "outside"), config.topics().stream().map(GuideConfig.Topic::id).toList());
        GuideConfig.Topic maps = config.topic("MAPS").orElseThrow();
        assertEquals(new GuideConfig.Topic("maps", "Maps", "", "https://example.org/guide/maps"), maps);
        assertEquals(new GuideConfig.Topic("query", "query", "Query", "https://example.org/guide?tab=one"),
                config.topic("query").orElseThrow());
        assertEquals("HTTPS://other.example.org/page", config.topic("outside").orElseThrow().url());
        assertTrue(config.topic("bad id").isEmpty());
    }

    @Test
    void urlsJoinTheBaseAndRejectAnythingButWebAddresses() {
        assertEquals("https://a.org/wiki", GuideConfig.url("https://a.org/wiki", ""));
        assertEquals("https://a.org/wiki/x", GuideConfig.url("https://a.org/wiki", "/x"));
        assertEquals("https://a.org/wiki/x", GuideConfig.url("https://a.org/wiki", "x"));
        assertEquals("https://a.org/wiki#s", GuideConfig.url("https://a.org/wiki", "#s"));
        assertEquals("http://b.org/", GuideConfig.url("https://a.org/wiki", "http://b.org/"));
        assertNull(GuideConfig.url("ftp://a.org", "/x"));
        assertNull(GuideConfig.url("wiki", "/x"));
        assertNull(GuideConfig.url("https://a.org", "/a b"));
    }

    @Test
    void aMissingBaseUrlUsesTheWebsiteAndAMissingTopicListIsEmpty() throws Exception {
        GuideConfig config = new GuideConfig();
        config.loadFromString("""
                topics:
                  cooking:
                    path: "/cooking"
                """);
        assertEquals(WIKI + "/cooking", config.topic("cooking").orElseThrow().url());

        config.loadFromString("base-url: \"https://example.org\"\n");
        assertTrue(config.topics().isEmpty());
    }

    @Test
    void messagesAreOneStringOrALineListWithBuiltInFallbacks() throws Exception {
        GuideConfig config = new GuideConfig();
        config.loadFromString("""
                messages:
                  header:
                    - "one"
                    - "two"
                  unknown: "nope"
                """);

        assertEquals(List.of("one", "two"), config.lines("header"));
        assertEquals("one\ntwo", config.line("header"));
        assertEquals(List.of("nope"), config.lines("unknown"));
        assertEquals("<red>No guide topics are set up yet.", config.line("empty"));
        assertEquals(List.of(""), config.lines("not-a-message"));
    }

    @Test
    void loadingAFileReplacesTheTopicsAndAFailedLoadKeepsThem() throws Exception {
        GuideConfig config = new GuideConfig();
        Path file = directory.resolve("guide.yml");
        Files.writeString(file, "topics:\n  war:\n    path: /war\n");

        assertTrue(config.load(file.toFile()));
        assertEquals(WIKI + "/war", config.topic("war").orElseThrow().url());

        Files.writeString(file, "topics: [unclosed\n");
        assertFalse(config.load(file.toFile()));
        assertFalse(config.load(directory.resolve("missing.yml").toFile()));
        assertTrue(config.topic("war").isPresent(), "A failed reload keeps the last good topics");
    }

    static GuideConfig bundled() throws Exception {
        GuideConfig config = new GuideConfig();
        config.loadFromString(Files.readString(Path.of("src/main/resources/guide.yml")));
        return config;
    }
}
