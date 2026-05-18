package dev.tailmux.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeProperties(Path path, Properties properties) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        byte[] bytes = render(properties).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String render(Properties properties) {
        ArrayList<String> names = new ArrayList<>(properties.stringPropertyNames());
        Collections.sort(names);
        StringBuilder out = new StringBuilder(renderLength(properties, names));
        for (String name : names) {
            out.append(escape(name)).append('=').append(escape(properties.getProperty(name))).append('\n');
        }
        return out.toString();
    }

    private static int renderLength(Properties properties, ArrayList<String> names) {
        int length = 0;
        for (String name : names) length += name.length() + properties.getProperty(name).length() + 2;
        return length;
    }

    private static String escape(String value) {
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean special = c == '\\' || c == '\n' || c == '\r' || c == '=';
            if (special && escaped == null) escaped = new StringBuilder(value.length() + 8).append(value, 0, i);
            if (escaped != null) {
                if (special) escaped.append('\\');
                escaped.append(c == '\n' ? 'n' : c == '\r' ? 'r' : c);
            }
        }
        return escaped == null ? value : escaped.toString();
    }
}
