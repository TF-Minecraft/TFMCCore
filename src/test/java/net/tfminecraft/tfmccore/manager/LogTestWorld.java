package net.tfminecraft.tfmccore.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Chunk-backed test world with no tracker-owned state. */
class LogTestWorld {
    private final Map<String, Chunk> chunks = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();

    Block block(int x, int y, int z) {
        String position = x + "," + y + "," + z;
        Block existing = blocks.get(position);
        if (existing != null) {
            return existing;
        }
        Block block = mock(Block.class);
        blocks.put(position, block);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(Material.OAK_LOG);
        Chunk chunk = chunks.computeIfAbsent((x >> 4) + "," + (z >> 4), key -> chunk());
        when(block.getChunk()).thenReturn(chunk);
        when(block.getRelative(any(BlockFace.class))).thenAnswer(call -> {
            BlockFace face = call.getArgument(0);
            return block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        });
        return block;
    }

    private static Chunk chunk() {
        Map<NamespacedKey, Byte> values = new HashMap<>();
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE)))
                .thenAnswer(call -> values.containsKey(call.getArgument(0)));
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));
        doAnswer(call -> {
            values.remove(call.getArgument(0));
            return null;
        }).when(data).remove(any(NamespacedKey.class));
        Chunk chunk = mock(Chunk.class);
        when(chunk.getPersistentDataContainer()).thenReturn(data);
        return chunk;
    }
}
