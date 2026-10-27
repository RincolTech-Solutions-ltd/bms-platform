package com.rincoltech.bms.lending.members;

import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import java.util.UUID;

/**
 * Adding a member from a line of a loan register that the existing loans import brings in (issue #626, ADR-102): the name
 * and phone the register gives, and no ID (the member's KYC is completed later, as for any member). Every rule of the
 * member form holds: the caller's scope in the branch, a phone in a Uganda form that no other member holds, and the plan's
 * member limit; the audit row is the form's. Runs in the caller's transaction, so a refused import adds no member.
 */
public interface MemberIntake {

    MemberSummary addFromRegister(UUID branchId, String fullName, String phone);
}
