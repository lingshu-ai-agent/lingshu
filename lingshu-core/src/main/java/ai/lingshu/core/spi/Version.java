package ai.lingshu.core.spi;

/**
 * 🆕 Story #003 — semver parser + compatibility checker (dsh §0.4 AC-02/AC-08).
 *
 * <p>Pure utility (no instance state). All methods static + thread-safe.
 *
 * <p>semver v1 simplification (per research.md D-01):
 * <ul>
 *   <li>Strict 3-segment {@code MAJOR.MINOR.PATCH}</li>
 *   <li>No 'v' prefix</li>
 *   <li>No pre-release / build metadata</li>
 *   <li>Non-negative integers</li>
 *   <li>No leading zeros (except "0" itself)</li>
 * </ul>
 *
 * <p>Compatibility rule (research.md D-03):
 * <ul>
 *   <li>Different major → incompatible</li>
 *   <li>Same major + Provider minor ≤ Slot minor → compatible</li>
 *   <li>Same major + same minor + Provider patch ≤ Slot patch → compatible</li>
 *   <li>Otherwise → incompatible</li>
 * </ul>
 */
public final class Version {

    private Version() {
        // utility — no instances
    }

    /**
     * Parse "MAJOR.MINOR.PATCH" → int[3]. Throws IllegalArgumentException on bad format.
     *
     * @param version semver string (strict 3-segment, no 'v' prefix, non-negative ints)
     * @return int array of length 3: [major, minor, patch]
     * @throws IllegalArgumentException if version is null / empty / malformed / has leading zeros
     */
    public static int[] parse(String version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
        String[] segs = version.split("\\.");
        if (segs.length != 3) {
            throw new IllegalArgumentException(
                "version '" + version + "' must have 3 dot-separated segments, got " + segs.length);
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = validateSegment(segs[i], i, version);
        }
        return out;
    }

    /**
     * Provider version ↔ Slot contract version compatibility check (research.md D-03).
     *
     * @param providerVer      Provider.version() return value (e.g. "1.0.0")
     * @param slotContractVer  Slot interface's CONTRACT_VERSION (e.g. "1.5.0")
     * @return true if compatible (backward-compat within major)
     * @throws IllegalArgumentException if either arg fails parse()
     */
    public static boolean isCompatible(String providerVer, String slotContractVer) {
        int[] pv = parse(providerVer);
        int[] sv = parse(slotContractVer);

        if (pv[0] != sv[0]) {
            // Major mismatch — incompatible in both directions
            throw new IllegalArgumentException(String.format(
                "Provider major %d != slot major %d (major version mismatch)",
                pv[0], sv[0]));
        }
        // Same major — check minor
        if (pv[1] > sv[1]) {
            // Provider minor > Slot minor → incompatible
            throw new IllegalArgumentException(String.format(
                "Provider minor %d > slot minor %d (provider uses APIs not yet declared)",
                pv[1], sv[1]));
        }
        if (pv[1] == sv[1] && pv[2] > sv[2]) {
            // Same major + same minor but Provider patch > Slot patch → incompatible
            throw new IllegalArgumentException(String.format(
                "Provider patch %d > slot patch %d (provider uses APIs not yet declared)",
                pv[2], sv[2]));
        }
        return true;
    }

    /**
     * int[3] → "MAJOR.MINOR.PATCH".
     *
     * @param version int array of length 3 with non-negative elements
     * @return formatted semver string
     * @throws IllegalArgumentException if length != 3 or any element is negative
     */
    public static String format(int[] version) {
        if (version == null) {
            throw new IllegalArgumentException("version array must not be null");
        }
        if (version.length != 3) {
            throw new IllegalArgumentException(
                "version array must have exactly 3 elements, got " + version.length);
        }
        for (int i = 0; i < 3; i++) {
            if (version[i] < 0) {
                throw new IllegalArgumentException(
                    "version segment " + i + " must be non-negative, got " + version[i]);
            }
        }
        return version[0] + "." + version[1] + "." + version[2];
    }

    /**
     * Validate a single semver segment: must be all digits, no leading zeros (except "0" itself),
     * non-negative after parseInt.
     */
    private static int validateSegment(String segment, int index, String full) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException(
                "version '" + full + "' segment " + index + " is empty");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                    "version '" + full + "' segment " + index + " '" + segment
                        + "' contains non-numeric character '" + c + "'");
            }
        }
        // No leading zeros except "0" itself
        if (segment.length() > 1 && segment.charAt(0) == '0') {
            throw new IllegalArgumentException(
                "version '" + full + "' segment " + index + " '" + segment
                    + "' has leading zero (semver disallows leading zeros)");
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "version '" + full + "' segment " + index + " '" + segment
                    + "' is not a valid integer", e);
        }
    }
}