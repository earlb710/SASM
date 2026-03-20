package com.sasm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates and deserialises the OS-specific JSON definition files shipped in the
 * {@code json/} directory of this repository.
 *
 * <p>The loader searches for the {@code json/} folder in the following order:
 * <ol>
 *   <li>Relative to the current working directory (typical when running from the
 *       project root with {@code mvn exec:java} or {@code java -jar …}).</li>
 *   <li>Two levels above the directory that contains the running JAR file (covers
 *       the Maven {@code target/} layout).</li>
 * </ol>
 */
public class JsonLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Maps the human-readable OS label shown in the UI to the corresponding JSON
     * file name (without the {@code .json} suffix).
     */
    public static String fileNameForOs(String osLabel) {
        return switch (osLabel.toLowerCase()) {
            case "linux"   -> "executable_linux";
            case "windows" -> "executable_windows";
            default -> throw new IllegalArgumentException("Unknown OS: " + osLabel);
        };
    }

    /**
     * Loads and parses the JSON definition file for the given OS.
     *
     * @param osLabel "Linux" or "Windows"
     * @return the parsed {@link OsDefinition}
     * @throws IOException if the file cannot be found or read
     */
    public static OsDefinition load(String osLabel) throws IOException {
        String fileName = fileNameForOs(osLabel) + ".json";

        // 1. Try cwd/json/<file>
        Path candidate = Paths.get("json", fileName);
        if (candidate.toFile().exists()) {
            return MAPPER.readValue(candidate.toFile(), OsDefinition.class);
        }

        // 2. Try <jar-dir>/../../json/<file>  (Maven target/ layout)
        try {
            File jarDir = new File(
                    JsonLoader.class.getProtectionDomain()
                              .getCodeSource()
                              .getLocation()
                              .toURI()).getParentFile();
            File fromJar = new File(jarDir, "../../json/" + fileName).getCanonicalFile();
            if (fromJar.exists()) {
                return MAPPER.readValue(fromJar, OsDefinition.class);
            }
        } catch (Exception ignored) {
            // fall through to error
        }

        throw new IOException("Cannot find JSON file '" + fileName
                + "'. Run the application from the repository root directory.");
    }
}
