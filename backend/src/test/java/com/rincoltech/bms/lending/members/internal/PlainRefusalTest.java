package com.rincoltech.bms.lending.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The member form's refusals in words that name no member (issue #626, ADR-102): one mapping for an import's check and for
 * a refusal its commit meets, so the two say the same, and never the member numbers the form's own messages may list.
 */
class PlainRefusalTest {

    static final MemberService SERVICE = new MemberService(null, null, null, null, null, null, null);

    static ApiException field(String name) {
        return ApiException.validation(List.of(new FieldProblem(name, "invalid", "Member M000123 has this.")));
    }

    @Test
    void eachRefusalOfTheFormIsSaidPlainlyAndNamesNoMember() {
        assertThat(SERVICE.plainRefusal(field("branch_id"))).startsWith("You may not add members in this branch.");
        assertThat(SERVICE.plainRefusal(field("full_name")))
                .isEqualTo("The member's name is empty or longer than 200 characters.");
        assertThat(SERVICE.plainRefusal(field("phone"))).startsWith("The member's phone must be a Uganda number");
        assertThat(SERVICE.plainRefusal(ApiException.rule("plan_limit_reached", "max_active_members reached")))
                .startsWith("The business's plan has no room for more members.");
        ApiException other = new ApiException(
                HttpStatus.CONFLICT, "duplicate_nin", "Duplicate NIN", "Member M000123 already has this NIN.");
        for (ApiException e : List.of(field("branch_id"), field("full_name"), field("phone"), other)) {
            assertThat(SERVICE.plainRefusal(e)).doesNotContain("M000123");
        }
    }
}
