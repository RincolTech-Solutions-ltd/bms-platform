-- V40: search helpers and trigram indexes (issue #241, ADR-039; docs/specs/search.md).
--
-- Expand only: two immutable functions and indexes. No table, column, constraint, policy, grant or
-- row changes, so the previous release runs unchanged on this schema (expand and contract, chapter 6
-- section 6.9). The old trigram indexes lending_members_name_trgm (V1) and retail_products_search
-- (V10) stay; once no release queries the raw columns they can be dropped by a later migration.
--
-- The number is the next free one above the highest on main when the pull request was marked ready;
-- the coordinator renumbers at merge (AGENTS.md, Flyway runs with outOfOrder off).
--
-- pg_trgm already exists (V1); the statement is repeated so this file stands alone. unaccent is not
-- used: it is not immutable and needs a second extension, so bms_fold carries its own table of the
-- Latin accents that names and codes in East Africa meet (French, Portuguese, Arabic and Nordic
-- spellings), listed in both cases so it does not depend on the server's locale.
--
-- Locks. Flyway runs this file in one transaction. Each index build takes a SHARE lock (reads
-- continue, writes to that table wait). If a long report holds the table, the migration fails after
-- 5 seconds instead of queueing every request behind it, and the deploy is simply re-run.
SET LOCAL lock_timeout = '5s';

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Lower case with Latin accents removed: "Jose" with an acute accent and "JOSE" both fold to "jose".
CREATE OR REPLACE FUNCTION bms_fold(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT lower(translate(t,
        U&'\00E0\00C0\00E1\00C1\00E2\00C2\00E3\00C3\00E4\00C4\00E5\00C5\0101\0100\0103\0102\0105\0104\00E7\00C7\0107\0106\010D\010C\010F\010E\0111\0110\00E8\00C8\00E9\00C9\00EA\00CA\00EB\00CB\0113\0112\0117\0116\0119\0118\011B\011A\011F\011E\00EC\00CC\00ED\00CD\00EE\00CE\00EF\00CF\012B\012A\012F\012E\0131\0049\0142\0141\013E\013D\00F1\00D1\0144\0143\0148\0147\00F2\00D2\00F3\00D3\00F4\00D4\00F5\00D5\00F6\00D6\00F8\00D8\014D\014C\0151\0150\0155\0154\0159\0158\015B\015A\0161\0160\015F\015E\0219\0218\0165\0164\0163\0162\021B\021A\00F9\00D9\00FA\00DA\00FB\00DB\00FC\00DC\016B\016A\016F\016E\0171\0170\0173\0172\00FD\00DD\00FF\0178\017A\0179\017C\017B\017E\017D',
        'aaaaaaaaaaaaaaaaaaccccccddddeeeeeeeeeeeeeeeeggiiiiiiiiiiiiiillllnnnnnnoooooooooooooooorrrrssssssssttttttuuuuuuuuuuuuuuuuyyyyzzzzzz')) $$;

-- The digits of a phone number as written: "+256 700 000 011" and "0700-000-011" lose the separators.
CREATE OR REPLACE FUNCTION bms_digits(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT regexp_replace(t, '\D', '', 'g') $$;

-- Members: the columns Search matches on a member list (name, member number, national id, both phones).
CREATE INDEX lending_members_fold_name ON lending_members USING gin (bms_fold(full_name) gin_trgm_ops);
CREATE INDEX lending_members_fold_no ON lending_members USING gin (bms_fold(member_no) gin_trgm_ops);
CREATE INDEX lending_members_fold_nin ON lending_members USING gin (bms_fold(national_id) gin_trgm_ops);
CREATE INDEX lending_members_digits_phone ON lending_members USING gin (bms_digits(phone_e164) gin_trgm_ops);
CREATE INDEX lending_members_digits_alt_phone ON lending_members USING gin (bms_digits(alt_phone_e164) gin_trgm_ops);

-- Products: the item pickers and the catalogue search by code and description.
CREATE INDEX retail_products_fold_code ON retail_products USING gin (bms_fold(code) gin_trgm_ops);
CREATE INDEX retail_products_fold_description ON retail_products USING gin (bms_fold(description) gin_trgm_ops);

-- Credit buyers: name and contact (a phone, written in any form).
CREATE INDEX retail_customers_fold_name ON retail_customers USING gin (bms_fold(name) gin_trgm_ops);
CREATE INDEX retail_customers_digits_contact ON retail_customers USING gin (bms_digits(contact) gin_trgm_ops);
