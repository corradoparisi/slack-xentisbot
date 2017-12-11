package ch.obermuhlner.slack.simplebot

interface SysCodeService : TranslationService {

    fun parse(sysCodeFile: String, sysSubsetFile: String)

    fun getSysCode(id: Long): SysCodeService.SysCode?

    fun findSysCodes(text: String): List<SysCodeService.SysCode>

    fun toMessage(syscode: SysCodeService.SysCode): String

    data class SysCode(
            val id: Long,
            val groupId: Long,
            val code: String,
            val name: String,
            val germanShort: String,
            val germanMedium: String,
            val englishShort: String,
            val englishMedium: String,
            val children: MutableList<Long> = mutableListOf(),
            val subsetEntries: MutableList<SysSubsetEntry> = mutableListOf())

    data class SysSubsetEntry(
            val id: Long,
            val sortNumber: Int,
            val defaultEntry: Boolean)
}