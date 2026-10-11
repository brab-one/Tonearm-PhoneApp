package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.TonearmServerInfo

/** Whose requests Lidarr's queue and wanted list hold, as the Tonearm server hands them out. */
enum class WhoseRequests(
    /** What a list of them is called ("your requests"); null when they're just Lidarr's. */
    val label: String?,
) {
    /** Lidarr's lists as they are, from a server that doesn't sort them by who asked. */
    UNSORTED(null),
    /** Only this user's own. */
    OWN("your requests"),
    /** Everyone's, each with who asked for it (an admin's). */
    EVERYONE("all requests");

    /** The tag for who asked for a queue item or wanted album: only in everyone's lists (see [requester]). */
    fun tag(requestedBy: List<String>, me: String?): String? = if (this == EVERYONE) requester(requestedBy, me) else null

    companion object {
        fun of(server: TonearmServerInfo?): WhoseRequests = when {
            server?.ownRequests != true -> UNSORTED
            server.admin -> EVERYONE
            else -> OWN
        }

        /**
         * Who asked for a queue item or wanted album, for its tag in everyone's list: "you" when only [me]
         * did, else "by alice" (you first, two names at most and how many more). Null when nobody's known.
         */
        fun requester(requestedBy: List<String>, me: String?): String? {
            val names = requestedBy.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            if (names.isEmpty()) return null
            val mine = me != null && names.any { it.equals(me, ignoreCase = true) }
            val others = names.filterNot { me != null && it.equals(me, ignoreCase = true) }
            if (others.isEmpty()) return "you"
            val shown = listOfNotNull("you".takeIf { mine }) + others
            return "by " + shown.take(2).joinToString(", ") + if (shown.size > 2) " +${shown.size - 2}" else ""
        }
    }
}
