package com.example.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MinedOreBlacklist {
	private static final String HEADER = "# mined ore coordinates: dimension<TAB>x<TAB>y<TAB>z";

	private final Path file;
	private final Set<Position> positions;

	private MinedOreBlacklist(Path file, Set<Position> positions) {
		this.file = file;
		this.positions = positions;
	}

	static MinedOreBlacklist load(Path file) throws IOException {
		Set<Position> positions = new HashSet<>();
		if (Files.exists(file)) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}

				String[] fields = line.split("\\t", -1);
				if (fields.length != 4 || fields[0].isBlank()) {
					throw new IOException("Invalid mined ore blacklist entry at line " + (i + 1) + ".");
				}

				try {
					positions.add(new Position(
							fields[0],
							Integer.parseInt(fields[1]),
							Integer.parseInt(fields[2]),
							Integer.parseInt(fields[3])
					));
				} catch (NumberFormatException exception) {
					throw new IOException("Invalid coordinates in mined ore blacklist at line " + (i + 1) + ".", exception);
				}
			}
		}

		return new MinedOreBlacklist(file, positions);
	}

	static MinedOreBlacklist load(Path file, Path legacyFile) throws IOException {
		if (!Files.exists(file) && Files.exists(legacyFile)) {
			Files.move(legacyFile, file);
		}
		return load(file);
	}

	synchronized boolean contains(String dimension, int x, int y, int z) {
		return positions.contains(new Position(dimension, x, y, z));
	}

	synchronized boolean add(String dimension, int x, int y, int z) throws IOException {
		if (!positions.add(new Position(dimension, x, y, z))) {
			return false;
		}

		try {
			save();
		} catch (IOException exception) {
			throw new IOException(
					"Could not save mined ore blacklist to " + file + "; the position is blacklisted until the game exits.",
					exception
			);
		}
		return true;
	}

	private void save() throws IOException {
		Files.createDirectories(file.getParent());
		List<String> lines = new ArrayList<>();
		lines.add(HEADER);
		positions.stream()
				.sorted(Comparator.comparing(Position::dimension)
						.thenComparingInt(Position::x)
						.thenComparingInt(Position::y)
						.thenComparingInt(Position::z))
				.map(Position::toLine)
				.forEach(lines::add);

		Path temporaryFile = Files.createTempFile(file.getParent(), "mined-ores-", ".tmp");
		try {
			Files.write(
					temporaryFile,
					lines,
					StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING
			);
			try {
				Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryFile);
		}
	}

	private record Position(String dimension, int x, int y, int z) {
		private String toLine() {
			return dimension + "\t" + x + "\t" + y + "\t" + z;
		}
	}
}
