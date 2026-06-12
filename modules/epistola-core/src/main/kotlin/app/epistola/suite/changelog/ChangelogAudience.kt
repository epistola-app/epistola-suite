package app.epistola.suite.changelog

/**
 * Audience classification for a changelog entry and for the changelog viewer's selected filter.
 *
 * Entries are tagged in `CHANGELOG.md` with a leading badge — `**[user]**` or `**[dev]**`. Untagged
 * entries are [EVERYONE]. The product changelog dialog lets the viewer pick a view; an entry is shown
 * when its audience is visible under that view:
 *
 *  - [EVERYONE] entries are always shown.
 *  - [USER] entries are shown in the `USER` and `ALL` views.
 *  - [DEVELOPER] entries are shown in the `DEVELOPER` and `ALL` views.
 */
enum class ChangelogAudience {
    /** Untagged entries — relevant to everyone, always shown. */
    EVERYONE,

    /** User-facing entries (`**[user]**`). */
    USER,

    /** Developer-facing entries (`**[dev]**`). */
    DEVELOPER,

    /** Viewer-only "show everything" filter. Never assigned to an entry. */
    ALL,
    ;

    /** Whether an entry with [entryAudience] is visible when this value is the selected view. */
    fun shows(entryAudience: ChangelogAudience): Boolean = when (this) {
        ALL -> true
        USER -> entryAudience == EVERYONE || entryAudience == USER
        DEVELOPER -> entryAudience == EVERYONE || entryAudience == DEVELOPER
        EVERYONE -> entryAudience == EVERYONE
    }

    companion object {
        /** Parses a viewer-supplied `audience` query value into a view, defaulting to [USER]. */
        fun viewFromParam(value: String?): ChangelogAudience = when (value?.trim()?.lowercase()) {
            "all" -> ALL
            "dev", "developer", "developers" -> DEVELOPER
            "user", "users", "everyone" -> USER
            else -> USER
        }

        /** Parses an entry-level marker token (without brackets) into an audience, or null if unknown. */
        fun entryFromMarker(token: String): ChangelogAudience? = when (token.trim().lowercase()) {
            "user" -> USER
            "dev", "developer" -> DEVELOPER
            else -> null
        }
    }
}
