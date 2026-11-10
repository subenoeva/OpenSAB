package com.opensab.domain.contacts

/**
 * How the address book labels a number. Pure: the translation of the `type` integers of
 * `ContactsContract` lives in the seam, and the words the cluster shows live in the page.
 */
enum class PhoneKind { MOBILE, HOME, WORK, MAIN, OTHER }

/**
 * @param isPrimary the contact's default number. It goes first, and it is the only thing that
 *   reorders what the address book gave — which is the order the rider sees on his own phone.
 */
data class PhoneNumber(
    val value: String,
    val kind: PhoneKind,
    val isPrimary: Boolean = false
)

data class Contact(
    val id: Long,
    val displayName: String,
    val starred: Boolean,
    val numbers: List<PhoneNumber>
)
