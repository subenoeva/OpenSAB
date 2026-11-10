package com.opensab.app.source.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import com.opensab.domain.contacts.Contact
import com.opensab.domain.contacts.ContactDirectory
import com.opensab.domain.contacts.ContactMatcher
import com.opensab.domain.contacts.PhoneKind
import com.opensab.domain.contacts.PhoneNumber
import com.opensab.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The address book through `ContactsContract`.
 *
 * [search] goes through `Contacts.CONTENT_FILTER_URI`, the indexed lookup Android's own dialler
 * uses: it already does per-word prefixes and copes with a bare first name, a bare surname and the
 * two the other way round, which is why the book is not read into memory and matched here.
 *
 * Nothing here ranks: [ContactMatcher] orders what the provider gave.
 */
class AndroidContactDirectory(
    private val contentResolver: ContentResolver
) : ContactDirectory {

    override suspend fun search(query: String): List<Contact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(query)
        )
        readContacts(uri, selection = null, args = null, limit = SEARCH_ROW_LIMIT)
    }

    /**
     * **Uncapped, and that is the contract.** [ContactMatcher.MAX_TARGETS] governs the ranking and
     * nothing else: a search that came back wrong is escaped by speaking again, while a favourite
     * cut off the end cannot be reached at all. A cap here would also cut before the alphabetical
     * order, keeping whichever favourites the provider happened to hand over first.
     */
    override suspend fun starred(): List<Contact> = withContext(Dispatchers.IO) {
        readContacts(
            uri = ContactsContract.Contacts.CONTENT_URI,
            selection = "${ContactsContract.Contacts.STARRED} = 1",
            args = null,
            limit = Int.MAX_VALUE
        )
    }

    /**
     * Reads the contact rows and then their numbers, one query per contact.
     *
     * A contact with no number is dropped: a row that cannot be dialled is a press that does nothing.
     */
    private fun readContacts(
        uri: Uri,
        selection: String?,
        args: Array<String>?,
        limit: Int
    ): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.STARRED
                ),
                selection,
                args,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val starredColumn =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)

                while (cursor.moveToNext() && contacts.size < limit) {
                    val id = cursor.getLong(idColumn)
                    val numbers = readNumbers(id)
                    if (numbers.isEmpty()) continue
                    contacts += Contact(
                        id = id,
                        displayName = cursor.getString(nameColumn).orEmpty(),
                        starred = cursor.getInt(starredColumn) == 1,
                        numbers = numbers
                    )
                }
            }
        } catch (e: SecurityException) {
            // READ_CONTACTS was revoked between the gate and the query. The menu closes its rows on
            // the next mount; this ride just finds nobody.
            Log.w(e, "The address book is not readable")
        }
        return contacts
    }

    private fun readNumbers(contactId: Long): List<PhoneNumber> {
        val numbers = mutableListOf<PhoneNumber>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            val numberColumn =
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeColumn =
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            val primaryColumn =
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)

            while (cursor.moveToNext()) {
                val value = cursor.getString(numberColumn).orEmpty()
                if (value.isBlank()) continue
                numbers += PhoneNumber(
                    value = value,
                    kind = kindOf(cursor.getInt(typeColumn)),
                    isPrimary = cursor.getInt(primaryColumn) == 1
                )
            }
        }
        return numbers
    }

    private fun kindOf(type: Int): PhoneKind = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> PhoneKind.MOBILE
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> PhoneKind.HOME
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> PhoneKind.WORK
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> PhoneKind.MAIN
        else -> PhoneKind.OTHER
    }

    private companion object {
        /**
         * **The search only.** Every extra row costs a second query. Wider than
         * `ContactMatcher.MAX_TARGETS` on purpose: the ranking still gets to choose among candidates
         * the provider ordered differently.
         */
        const val SEARCH_ROW_LIMIT = 12
    }
}
