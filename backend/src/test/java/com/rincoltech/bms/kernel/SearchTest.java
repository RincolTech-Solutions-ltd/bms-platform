package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The parsing and cursor rules of the shared search helper (ADR-039); the SQL is proved in SearchLendingIT and SearchRetailIT. */
class SearchTest {

    static final Search.Fields NAME_AND_PHONE =
            Search.Fields.of().names("full_name").codes("member_no").phones("phone_e164");

    @Test
    void nothingToSearchIsNull() {
        assertThat(Search.parse(null)).isNull();
        assertThat(Search.parse("")).isNull();
        assertThat(Search.parse(" \t\n ")).isNull();
    }

    @Test
    void textIsSplitOnWhiteSpaceAndLimited() {
        assertThat(Search.parse("  john   doe ").terms()).containsExactly("john", "doe");
        assertThat(Search.parse("a b c d e f g h").terms()).hasSize(Search.MAX_TERMS);
        assertThat(Search.parse("x".repeat(500)).terms().getFirst()).hasSize(Search.MAX_LENGTH);
    }

    @Test
    void everyWrittenFormOfAPhoneHasTheSameNationalDigits() {
        for (String written : new String[] {"0700000111", "700000111", "256700000111", "+256700000111"}) {
            String digits = written.replaceAll("\\D", "");
            assertThat(Search.national(digits)).as(written).isEqualTo("700000111");
        }
        assertThat(Search.parse("+256 700 000 111").wholePhone()).isEqualTo("700000111");
        assertThat(Search.parse("0700 000 111").wholePhone()).isEqualTo("700000111");
    }

    @Test
    void aShortOrMixedTextIsNotAPhone() {
        assertThat(Search.parse("07 00").wholePhone()).isEmpty();
        assertThat(Search.parse("john 0700000111").wholePhone()).isEmpty();
        assertThat(Search.parse("0700000111").wholePhone()).isEmpty();
    }

    @Test
    void likeCharactersAreEscaped() {
        assertThat(Search.escape("50%")).isEqualTo("50\\%");
        assertThat(Search.escape("a_b")).isEqualTo("a\\_b");
        assertThat(Search.escape("a\\b")).isEqualTo("a\\\\b");
        Search.Match m = Search.parse("50% a_b").match(NAME_AND_PHONE);
        assertThat(m.params()).containsEntry("s0", "%50\\%%").containsEntry("s1", "%a\\_b%");
        assertThat(m.where()).doesNotContain("50%").doesNotContain("a_b");
    }

    @Test
    void everyTermMustMatchSomewhereAndTheRankIsPrefixThenWordThenAny() {
        Search.Match m = Search.parse("john doe").match(NAME_AND_PHONE);
        assertThat(m.where()).contains(" AND ");
        assertThat(m.where()).contains("bms_fold(full_name) LIKE bms_fold(:s0)");
        assertThat(m.where()).contains("bms_fold(member_no) LIKE bms_fold(:s1)");
        assertThat(m.rank())
                .startsWith("CASE WHEN")
                .contains("THEN 0")
                .contains("THEN 1")
                .contains("ELSE 2");
        assertThat(m.params()).containsEntry("sp", "john%").containsEntry("sw", "% john%");
    }

    @Test
    void aPhoneColumnIsMatchedByDigitsOnlyForALongEnoughNumber() {
        Search.Match number = Search.parse("0700 000 111").match(NAME_AND_PHONE);
        assertThat(number.where()).contains("bms_digits(phone_e164) LIKE :dw");
        assertThat(number.params()).containsEntry("dw", "%700000111%");

        Search.Match shortText = Search.parse("700").match(NAME_AND_PHONE);
        assertThat(shortText.where()).doesNotContain("bms_digits");

        Search.Match noPhoneColumn =
                Search.parse("0700000111").match(Search.Fields.of().names("name"));
        assertThat(noPhoneColumn.where()).doesNotContain("bms_digits");
    }

    @Test
    void aGuardedColumnMatchesOnlyWhereTheGuardHolds() {
        Search.Fields fields = Search.Fields.of()
                .names("name")
                .guarded("m.branch_id IN (:memberBranches)", Search.Fields.of().codes("national_id"));
        Search.Match m = Search.parse("abc").match(fields);
        assertThat(m.where())
                .contains("(m.branch_id IN (:memberBranches) AND bms_fold(national_id) LIKE bms_fold(:s0))");
        assertThat(m.where()).contains("bms_fold(name) LIKE bms_fold(:s0)");
    }

    @Test
    void theRankTravelsInTheCursorOfASearchedListOnly() {
        String inner = Cursor.encode("M000007");
        assertThat(Search.wrap(false, 1, inner)).isEqualTo(inner);
        String wrapped = Search.wrap(true, 2, inner);
        Search.After after = Search.unwrap(true, wrapped);
        assertThat(after.rank()).isEqualTo(2);
        assertThat(after.cursor()).isEqualTo(inner);
        assertThat(Search.unwrap(false, inner)).isEqualTo(new Search.After(0, inner));
        assertThat(Search.unwrap(true, null)).isEqualTo(new Search.After(0, null));
    }

    @Test
    void aCursorWithoutARankIsRefusedForASearchedList() {
        assertThatThrownBy(() -> Search.unwrap(true, Cursor.encode("M000007"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Search.unwrap(true, Cursor.encode("x~M000007")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Search.unwrap(true, "!!not base64!!")).isInstanceOf(ApiException.class);
    }
}
