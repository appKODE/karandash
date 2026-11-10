package io.karandash.loader.wasm

/**
 * Reads the export names from a WASM binary's export section, so the bridge can tell which optional
 * ABI symbols a guest provides (the engines expose no export query). Returns null for bytes that are
 * not a well-formed module; callers then treat every symbol as exported.
 */
internal object WasmExportNames {
    private const val HEADER_SIZE = 8
    private const val TYPE_SECTION = 1
    private const val IMPORT_SECTION = 2
    private const val FUNCTION_SECTION = 3
    private const val EXPORT_SECTION = 7
    private const val KIND_FUNC = 0
    private const val KIND_TABLE = 1
    private const val KIND_MEMORY = 2
    private const val KIND_GLOBAL = 3
    private const val KIND_TAG = 4
    private const val LIMITS_HAS_MAX = 1
    private const val LEB_PAYLOAD_MASK = 0x7F
    private const val LEB_CONTINUE = 0x80
    private const val LEB_SHIFT = 7
    private const val BYTE_MASK = 0xFF
    private val MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D)

    fun of(bytes: ByteArray): Set<String>? {
        if (bytes.size < HEADER_SIZE || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val reader = Reader(bytes, HEADER_SIZE)
        while (reader.hasMore()) {
            val id = reader.byte() ?: return null
            val size = reader.leb() ?: return null
            if (id == EXPORT_SECTION) return reader.exports()
            if (!reader.skip(size)) return null
        }
        return emptySet()
    }

    /**
     * Parameter count of every exported function, by export name: the call shape a guest was built for
     * (the C ABI passes each string as `ptr, len`; the single-JSON WASM shape passes one JSON). Empty for bytes
     * that are not a well-formed module.
     */
    fun paramCounts(bytes: ByteArray): Map<String, Int> {
        if (bytes.size < HEADER_SIZE || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return emptyMap()
        val reader = Reader(bytes, HEADER_SIZE)
        val typeParams = mutableListOf<Int>()
        // Function index space: imported functions first, then the module's own.
        val funcTypes = mutableListOf<Int>()
        while (reader.hasMore()) {
            val id = reader.byte() ?: return emptyMap()
            val size = reader.leb() ?: return emptyMap()
            val end = reader.pos + size
            val section: List<Int>? =
                when (id) {
                    TYPE_SECTION -> {
                        reader.typeParams()?.also(typeParams::addAll)
                    }

                    IMPORT_SECTION -> {
                        reader.importedFuncTypes()?.also(funcTypes::addAll)
                    }

                    FUNCTION_SECTION -> {
                        reader.indices()?.also(funcTypes::addAll)
                    }

                    EXPORT_SECTION -> {
                        return reader
                            .funcExports()
                            .orEmpty()
                            .mapNotNull { (name, index) ->
                                funcTypes.getOrNull(index)?.let(typeParams::getOrNull)?.let { name to it }
                            }.toMap()
                    }

                    else -> {
                        emptyList()
                    }
                }
            if (section == null || end > bytes.size) return emptyMap()
            reader.pos = end
        }
        return emptyMap()
    }

    /** @lifecycle per-run -- one pass over one byte array */
    private class Reader(
        private val bytes: ByteArray,
        var pos: Int,
    ) {
        fun name(): String? {
            val length = leb() ?: return null
            val start = pos
            return if (skip(length)) bytes.decodeToString(start, start + length) else null
        }

        fun indices(): List<Int>? {
            val count = leb() ?: return null
            return List(count) { leb() ?: return null }
        }

        /** Each func type: `0x60`, param types, result types; the param count of each. */
        fun typeParams(): List<Int>? {
            val count = leb() ?: return null
            return List(count) {
                byte() ?: return null
                val params = leb() ?: return null
                val results = if (skip(params)) leb() else null
                if (results == null || !skip(results)) return null
                params
            }
        }

        /** The type index of each imported function; other import kinds are skipped. */
        fun importedFuncTypes(): List<Int>? {
            val count = leb() ?: return null
            val types = mutableListOf<Int>()
            repeat(count) {
                if (name() == null || name() == null) return null
                val ok =
                    when (byte()) {
                        KIND_FUNC -> leb()?.let(types::add) != null
                        KIND_TABLE -> byte() != null && limits()
                        KIND_MEMORY -> limits()
                        KIND_GLOBAL -> byte() != null && byte() != null
                        KIND_TAG -> byte() != null && leb() != null
                        else -> false
                    }
                if (!ok) return null
            }
            return types
        }

        private fun limits(): Boolean {
            val flags = byte() ?: return false
            if (leb() == null) return false
            return flags and LIMITS_HAS_MAX == 0 || leb() != null
        }

        /** Exported functions: name to function index. */
        fun funcExports(): List<Pair<String, Int>>? {
            val count = leb() ?: return null
            val exports = mutableListOf<Pair<String, Int>>()
            repeat(count) {
                val name = name() ?: return null
                val kind = byte() ?: return null
                val index = leb() ?: return null
                if (kind == KIND_FUNC) exports += name to index
            }
            return exports
        }

        fun hasMore() = pos < bytes.size

        fun byte(): Int? = if (pos < bytes.size) bytes[pos++].toInt() and BYTE_MASK else null

        fun skip(count: Int): Boolean {
            if (count < 0 || count > bytes.size - pos) return false
            pos += count
            return true
        }

        fun leb(): Int? {
            var result = 0
            var shift = 0
            while (shift < Int.SIZE_BITS) {
                val b = byte() ?: return null
                result = result or ((b and LEB_PAYLOAD_MASK) shl shift)
                if (b and LEB_CONTINUE == 0) return result
                shift += LEB_SHIFT
            }
            return null
        }

        /** Each entry: name (length-prefixed UTF-8), kind byte, index. */
        fun exports(): Set<String>? {
            val count = leb() ?: return null
            val names = mutableSetOf<String>()
            repeat(count) {
                val length = leb() ?: return null
                val start = pos
                if (!skip(length)) return null
                names += bytes.decodeToString(start, start + length)
                byte() ?: return null
                leb() ?: return null
            }
            return names
        }
    }
}
