package ai.lingshu.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #003 — {@link Version} utility tests (13 scenarios).
 *
 * <p>Covers contracts/slot-version-compat.md §2.2 compatibility rules + strict
 * 3-segment semver validation per research.md D-01.
 */
class VersionTest {

    @Nested
    @DisplayName("parse() — strict 3-segment semver")
    class ParseTests {

        @Test
        @DisplayName("parse_validStandard_1_0_0_returnsThreeSegments")
        void parse_validStandard_1_0_0_returnsThreeSegments() {
            assertThat(Version.parse("1.0.0")).containsExactly(1, 0, 0);
        }

        @Test
        @DisplayName("parse_validWithNonZero_2_5_3_returnsThreeSegments")
        void parse_validWithNonZero_2_5_3_returnsThreeSegments() {
            assertThat(Version.parse("2.5.3")).containsExactly(2, 5, 3);
        }

        @Test
        @DisplayName("parse_validLargeNumbers_returnsThreeSegments")
        void parse_validLargeNumbers_returnsThreeSegments() {
            assertThat(Version.parse("1.10.100")).containsExactly(1, 10, 100);
        }

        @Test
        @DisplayName("parse_null_throws")
        void parse_null_throws() {
            assertThatThrownBy(() -> Version.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("parse_empty_throws")
        void parse_empty_throws() {
            assertThatThrownBy(() -> Version.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("parse_tooFewSegments_v1_throws")
        void parse_tooFewSegments_v1_throws() {
            assertThatThrownBy(() -> Version.parse("v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 dot-separated segments");
        }

        @Test
        @DisplayName("parse_tooManySegments_1_0_0_0_throws")
        void parse_tooManySegments_1_0_0_0_throws() {
            assertThatThrownBy(() -> Version.parse("1.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 dot-separated segments");
        }

        @Test
        @DisplayName("parse_vPrefix_throws")
        void parse_vPrefix_throws() {
            assertThatThrownBy(() -> Version.parse("v1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parse_leadingZero_01_0_0_throws")
        void parse_leadingZero_01_0_0_throws() {
            assertThatThrownBy(() -> Version.parse("01.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading zero");
        }

        @Test
        @DisplayName("parse_nonNumeric_throws")
        void parse_nonNumeric_throws() {
            assertThatThrownBy(() -> Version.parse("1.0.x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-numeric character");
        }
    }

    @Nested
    @DisplayName("isCompatible() — backward-compat within major")
    class IsCompatibleTests {

        @Test
        @DisplayName("isCompatible_exactMatch_returnsTrue")
        void isCompatible_exactMatch_returnsTrue() {
            assertThat(Version.isCompatible("1.0.0", "1.0.0")).isTrue();
        }

        @Test
        @DisplayName("isCompatible_providerMinorLags_returnsTrue")
        void isCompatible_providerMinorLags_returnsTrue() {
            // Provider 1.5.3 vs Slot 1.10.0 — minor 5 ≤ 10
            assertThat(Version.isCompatible("1.5.3", "1.10.0")).isTrue();
        }

        @Test
        @DisplayName("isCompatible_providerPatchLags_returnsTrue")
        void isCompatible_providerPatchLags_returnsTrue() {
            assertThat(Version.isCompatible("1.0.0", "1.0.5")).isTrue();
        }

        @Test
        @DisplayName("isCompatible_providerMinorAhead_throws")
        void isCompatible_providerMinorAhead_throws() {
            assertThatThrownBy(() -> Version.isCompatible("1.10.0", "1.5.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider minor")
                .hasMessageContaining("10")
                .hasMessageContaining("5");
        }

        @Test
        @DisplayName("isCompatible_providerPatchAhead_throws")
        void isCompatible_providerPatchAhead_throws() {
            assertThatThrownBy(() -> Version.isCompatible("1.0.5", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider patch");
        }

        @Test
        @DisplayName("isCompatible_majorMismatch_throws")
        void isCompatible_majorMismatch_throws() {
            assertThatThrownBy(() -> Version.isCompatible("2.0.0", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("major version mismatch");
        }

        @Test
        @DisplayName("isCompatible_providerTooOldMajor_throws")
        void isCompatible_providerTooOldMajor_throws() {
            assertThatThrownBy(() -> Version.isCompatible("1.0.0", "2.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("major version mismatch");
        }

        @Test
        @DisplayName("isCompatible_invalidProviderSemver_throws")
        void isCompatible_invalidProviderSemver_throws() {
            assertThatThrownBy(() -> Version.isCompatible("v1", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isCompatible_invalidSlotSemver_throws")
        void isCompatible_invalidSlotSemver_throws() {
            assertThatThrownBy(() -> Version.isCompatible("1.0.0", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("format() — int[3] → MAJOR.MINOR.PATCH")
    class FormatTests {

        @Test
        @DisplayName("format_validRoundTrip_parsesBack")
        void format_validRoundTrip_parsesBack() {
            int[] arr = new int[] { 1, 2, 3 };
            String formatted = Version.format(arr);
            assertThat(formatted).isEqualTo("1.2.3");
            assertThat(Version.parse(formatted)).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("format_zeroPatch_returnsX_X_0")
        void format_zeroPatch_returnsX_X_0() {
            assertThat(Version.format(new int[] { 1, 0, 0 })).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("format_null_throws")
        void format_null_throws() {
            assertThatThrownBy(() -> Version.format(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("format_wrongLength_throws")
        void format_wrongLength_throws() {
            assertThatThrownBy(() -> Version.format(new int[] { 1, 0 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 3 elements");
        }

        @Test
        @DisplayName("format_negativeSegment_throws")
        void format_negativeSegment_throws() {
            assertThatThrownBy(() -> Version.format(new int[] { 1, -1, 0 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }
}
