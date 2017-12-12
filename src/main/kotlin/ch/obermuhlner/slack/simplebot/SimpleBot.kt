package ch.obermuhlner.slack.simplebot

import ch.obermuhlner.slack.simplebot.xentis.XentisDbSchemaService
import ch.obermuhlner.slack.simplebot.xentis.XentisKeyMigrationService
import ch.obermuhlner.slack.simplebot.xentis.XentisPropertiesTranslationService
import ch.obermuhlner.slack.simplebot.xentis.XentisSysCodeService
import ch.obermuhlner.slack.simplebot.TranslationService.Translation
import java.util.Properties
import java.io.FileReader
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.SlackUser
import com.google.gson.GsonBuilder
import com.google.gson.FieldNamingPolicy
import com.ullink.slack.simpleslackapi.SlackSession
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import java.io.BufferedReader
import java.util.regex.Pattern
import java.io.PrintWriter
import java.io.StringWriter

class SimpleBot(
		private val sysCodeService: SysCodeService = XentisSysCodeService(),
		private val propertiesTranslations: PropertiesTranslationService = XentisPropertiesTranslationService(),
		private val dbSchemaService: DbSchemaService = XentisDbSchemaService(),
		private val keyMigrationService: KeyMigrationService = XentisKeyMigrationService()) {

	private lateinit var session: SlackSession
	private lateinit var user: SlackUser
	private var adminUser: SlackUser? = null

	private val observedChannelIds = HashSet<String>()

	private val translations = mutableSetOf<Translation>()

	fun start () {
		loadData()
		
		session.addMessagePostedListener({ event, _ ->
			handleMessagePosted(event)
		})
		
		println("Ready")
	}
	
	private fun loadData() {
		val properties = loadProperties("simplebot.properties")
		
		val apiKey = properties.getProperty("api.key")
		session = connected(SlackSessionFactory.createWebSocketSlackSession(apiKey))
		
		user = session.user()
		adminUser = findUser(properties.getProperty("admin.user"))
		
		val xentisSchemaFileName = properties.getProperty("xentis.schema")
		if (xentisSchemaFileName != null) {
			dbSchemaService.parse(xentisSchemaFileName)
		}
		
		val xentisKeyMigrationFileName = properties.getProperty("xentis.keymigration")
		if (xentisKeyMigrationFileName != null) {
			keyMigrationService.parse(xentisKeyMigrationFileName)
			translations.addAll(keyMigrationService.translations)
		}

		val xentisSysCodeFileName = properties.getProperty("xentis.syscode")
		val xentisSysSubsetFileName = properties.getProperty("xentis.syssubset")
		if (xentisSysCodeFileName != null && xentisSysSubsetFileName != null) {
			sysCodeService.parseSysCodes(FileReader(xentisSysCodeFileName))
            sysCodeService.parseSysSubsets(FileReader(xentisSysSubsetFileName))
		}
		
		loadPropertiesTranslations(properties)
		translations.addAll(propertiesTranslations.translations)
		translations.addAll(sysCodeService.translations)
	}

	private fun findUser(user: String?): SlackUser? {
		if (user == null) {
			return null
		}
		
		val userById = session.findUserById(user)
		if (userById != null) {
			return userById
		}
		
		return session.findUserByUserName(user)
	}
	
	private fun handleMessagePosted(event: SlackMessagePosted) {
		try {
			if (event.sender.id != user.id) {
				val message = event.messageContent
				val directMessage = parseCommand(user.tag(), message)
				if (directMessage != null) {
					respondToMessage(event, directMessage) 
				} else if (event.channel.isDirect || observedChannelIds.contains(event.channel.id)) {
					respondToMessage(event, event.messageContent) 
				}
			}
		} catch (ex: Exception) {
			handleException("""
					|*Failed to handle message:*
					|from: ${event.sender.realName}
					|channel: ${event.channel.name}
					|content: ${event.messageContent}
					""".trimMargin(), ex)
		}
	}
	
	private fun handleException(message: String, ex: Exception) {
		ex.printStackTrace()
		
		if (adminUser != null) {
			val stringWriter = StringWriter()
			ex.printStackTrace(PrintWriter(stringWriter))
			
			session.sendMessageToUser(adminUser, message, null)
			session.sendFileToUser(adminUser, stringWriter.toString().toByteArray(), "Stacktrace.txt")
		}
	}
		
	fun loadPropertiesTranslations(properties: Properties) {
		propertiesTranslations.clear()

		var translationIndex = 0
		
		var success: Boolean
		do {
			translationIndex++
			val file1 = properties.getProperty("translation.${translationIndex}.source.properties")
			val file2 = properties.getProperty("translation.${translationIndex}.target.properties")

			if (file1 != null && file2 != null) {
				propertiesTranslations.parse(loadProperties(file1), loadProperties(file2))
				success = true
			} else {
				success = false
			}
		} while (success)
	}
	
	fun respondToMessage(event: SlackMessagePosted , messageContent: String) {
		println(messageContent)
		
		val args = messageContent.split(Pattern.compile("\\s+"))
		
		if (args.isEmpty() || isCommand(args, "", 0) || isCommand(args, "help", 0)) {
			respondHelp(event)
			return
		}

		if (isCommand(args, "refresh", 0)) {
			loadData()
			respondStatus(event)
			return
		}
		
		if (isCommand(args, "status", 0)) {
			respondStatus(event)
			return
		}
		
		if (isCommand(args, "translate", 1)) {
			respondSearchTranslations(event, args[1])
			return
		}
		
		if (isCommand(args, "id", 1)) {
			val idText = args[1]
			val xentisId = parseXentisId(idText)
			if (xentisId != null) {
				respondAnalyzeXentisId(event, xentisId.first, xentisId.second)
			} else {
				session.sendMessage(event.channel, "This is a not a valid Xentis id: $idText. It must be 16 hex digits.")
			}
			return
		}

		if (isCommand(args, "syscodes", 1)) {
			respondXentisPartialSysCodeText(event, args[1])
			return
		}
		
		if (isCommand(args, "syscode", 1)) {
			val syscodeText = args[1]
			val xentisId = parseXentisId(syscodeText)
			if (xentisId != null) {
				respondXentisSysCodeId(event, xentisId.first)
			} else {
				respondXentisSysCodeText(event, syscodeText)
			}
			return
		}

		if (isCommand(args, "classpart", 1)) {
			val classPartText = args[1]
			val xentisId = parseXentisId(classPartText, 4)
			if (xentisId != null) {
				respondAnalyzeXentisClassPart(event, xentisId.second)
			} else {
				session.sendMessage(event.channel, "This is a not a valid Xentis classpart: $classPartText. It must be 4 hex digits.")
			}
			return
		}

		if (isCommand(args, "tables", 1)) {
			respondXentisPartialTableName(event, args[1])
			return
		}
		
		if (isCommand(args, "table", 1)) {
			respondXentisTableName(event, args[1])
			return
		}
		
		if (isCommand(args, "key", 1)) {
			respondXentisKey(event, args[1])
			return
		}

		if (isCommand(args, "dec", 1)) {
			respondNumberConversion(event, args[1].removeSuffix("L"), 10)
			return
		}
		
		if (isCommand(args, "hex", 1)) {
			respondNumberConversion(event, args[1].removePrefix("0x").removeSuffix("L"), 16)
			return
		}
		
		if (isCommand(args, "bin", 1)) {
			respondNumberConversion(event, args[1].removePrefix("0b").removeSuffix("L"), 2)
			return
		}

		// generic parsing
				
		val xentisId = parseXentisId(messageContent)
		if (xentisId != null) {
			respondAnalyzeXentisId(event, xentisId.first, xentisId.second, failMessage=false)
			
			respondXentisSysCodeId(event, xentisId.first, failMessage=false)
			return
		}
		
		val xentisClassPart = parseXentisId(messageContent, 4)
		if (xentisClassPart != null) {
			respondAnalyzeXentisClassPart(event, messageContent, failMessage=false)
		}
		
		if (args[0].startsWith("0x")) {
			respondNumberConversion(event, args[0].removePrefix("0x").removeSuffix("L"), 16)
		} else if (args[0].startsWith("0b")) {
			respondNumberConversion(event, args[0].removePrefix("0b").removeSuffix("L"), 2)
		} else {
			respondNumberConversion(event, args[0].removeSuffix("L"), 10, failMessage=false, introMessage=true)
			respondNumberConversion(event, args[0].removePrefix("0x").removeSuffix("L"), 16, failMessage=false, introMessage=true)
			respondNumberConversion(event, args[0].removePrefix("0b").removeSuffix("L"), 2, failMessage=false, introMessage=true)
		}
		
		respondXentisSysCodeText(event, messageContent, failMessage=false)
		respondXentisTableName(event, messageContent, failMessage=false)
		respondSearchTranslations(event, messageContent)
	}
	
	private fun isCommand(args: List<String>, command: String, argCount: Int): Boolean {
		return args.size >= (argCount + 1) && args[0] == command
	}
	
	private fun parseXentisId(text: String, length: Int = 16): Pair<Long, String>? {
		var hexText = text
		var id = text.toLongOrNull(16)

		if (id == null) {
			id = text.toLongOrNull(10)
			if (id == null) {
				return null
			}
			hexText = id.toString(16)
		}
		
		if (hexText.length != length) {
			return null
		} 
		
		return Pair(id, hexText)
	}
	
	private fun parseCommand(command: String, line: String): String? {
		if (line.startsWith(command)) {
			return line.substring(command.length).trim()
		}
		return null
	}
	
	private fun respondHelp(event: SlackMessagePosted) {
		val bot = "@" + user.userName
		session.sendMessage(event.channel, """
				|You can ask me questions by giving me a command with an appropriate argument.
				|Try it out by asking one of the following lines (just copy and paste into a new message):
				|$bot help
				|$bot id 108300000012be3c
				|$bot classpart 1083
				|$bot tables zuord
				|$bot table portfolio
				|$bot syscode 10510000940000aa
				|$bot syscode C_InstParam_PseudoVerfall
				|$bot key 1890
				|$bot hex c0defeed
				|$bot dec 1234567890
				|$bot translate interest
				|
				|If you talk with me without specifying a command, I will try to answer as best as I can (maybe giving multiple answers).
				|Please try one of the following:
				|$bot 108300000012be3c
				|$bot 1083
				|$bot portfolio
				|$bot interest
				|
				|If you talk with me in a direct chat you do not need to prefix the messages with my name $bot.
				|Please try one of the following:
				|108300000012be3c
				|1083
				|portfolio
				|interest
				""".trimMargin())
	}
	
	private fun respondStatus(event: SlackMessagePosted) {
			session.sendMessage(event.channel, """
					|${dbSchemaService.getTableNames("").size} database tables
					|${sysCodeService.findSysCodes("").size} syscodes
					|${keyMigrationService.translations.size} keymigration translations
					|${sysCodeService.translations.size} syscode translations
					|${propertiesTranslations.translations.size} properties translations
					|${translations.size} total translations
					""".trimMargin())
	}
	
	private fun respondAnalyzeXentisId(event: SlackMessagePosted, id: Long, text: String, failMessage: Boolean=true) {
		session.sendMessage(event.channel, "This is a Xentis id: $text = decimal $id")
		
		respondAnalyzeXentisClassPart(event, text, failMessage=failMessage)
	}

	private fun respondXentisSysCodeId(event: SlackMessagePosted, id: Long, failMessage: Boolean=true) {
		val syscode = sysCodeService.getSysCode(id)
		
		if (syscode == null) {
			if (failMessage) {
				session.sendMessage(event.channel, "This is not a valid a Xentis syscode: ${id.toString(16)}")
			}
			return
		}
		
		session.sendMessage(event.channel, sysCodeService.toMessage(syscode))
	}

	private fun respondXentisPartialSysCodeText(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val syscodeResults = sysCodeService.findSysCodes(text)
		
		if (syscodeResults.isEmpty()) {
			if (failMessage) {
				session.sendMessage(event.channel, "No matching Xentis syscodes found.")
			}
			return
		}
		
		val syscodes = plural(syscodeResults.size, "syscode", "syscodes")
		var message = "Found ${syscodeResults.size} $syscodes:\n"
		
		for (syscode in syscodeResults) {
			message += "${syscode.id.toString(16)} `${syscode.name}`\n"
		}
		
		session.sendMessage(event.channel, message)
	}
		
	private fun respondXentisSysCodeText(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val syscodeResults = sysCodeService.findSysCodes(text)
		
		if (syscodeResults.isEmpty()) {
			if (failMessage) {
				session.sendMessage(event.channel, "No matching Xentis syscodes found.")
			}
			return
		}
		
		val syscodes = plural(syscodeResults.size, "syscode", "syscodes")
		var message = "Found ${syscodeResults.size} $syscodes:\n"
		
		limitedForLoop(10, 0, syscodeResults, { syscode ->
			message += sysCodeService.toMessage(syscode)
			message += "\n"
		}, {_ ->
			message += "..."
		})
		
		session.sendMessage(event.channel, message)
	}
	
	private fun respondAnalyzeXentisClassPart(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val xentisClassPartText = text.substring(0, 4)
		val xentisClassPart = java.lang.Long.parseLong(xentisClassPartText, 16) and 0xfff
		val tableName = dbSchemaService.getTableName(xentisClassPart)
		
		if (tableName != null) {
			session.sendMessage(event.channel, "The classpart $xentisClassPartText indicates a Xentis table $tableName")
		} else {
			if (failMessage) {
				session.sendMessage(event.channel, "This is not a Xentis classpart: $xentisClassPartText.")
			}
		}
	}
	
	private fun respondXentisTableName(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val tableName = text.toUpperCase()
		
		val table = dbSchemaService.getTable(tableName)
		if (table != null) {
			session.sendFile(event.channel, table.toMessage().toByteArray(), "TABLE_$tableName.txt")
		}
		
		val tableId = dbSchemaService.getTableId(tableName)
		if (tableId != null) {
			val xentisClassPartText = (tableId or 0x1000).toString(16).padStart(4, '0')
			session.sendMessage(event.channel, "The classpart of the Xentis table $tableName is $xentisClassPartText")
		} else {
			if (failMessage) {
				session.sendMessage(event.channel, "This is not a Xentis table: $tableName.")
			}
		}
	}
	
	private fun respondXentisPartialTableName(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val tableNames = dbSchemaService.getTableNames(text).sorted()
		
		if (tableNames.isEmpty()) {
			if (failMessage) {
				session.sendMessage(event.channel, "_No matching tables found._")
			}
			return
		}
		
		val tables = plural(tableNames.size, "table", "tables")
		var message = "_Found ${tableNames.size} matching $tables._\n"
		for(tableName in tableNames) {
			message += tableName + "\n"
		}
		
		session.sendMessage(event.channel, message)
	}

	private fun respondXentisKey(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		val id = text.toIntOrNull()
		if (id == null) {
			if (failMessage) {
				session.sendMessage(event.channel, "Not a valid Xentis key id (must be an integer value): $text")
			}
			return
		}
		
		val keyNode = keyMigrationService.getKeyNode(id)
		if (keyNode == null) {
			if (failMessage) {
				session.sendMessage(event.channel, "No Xentis key node found for id: $id")
			}
			return
		}
		
		session.sendMessage(event.channel, keyMigrationService.toMessage(keyNode))
	}
	
	private fun respondNumberConversion(event: SlackMessagePosted, text: String, base: Int, failMessage: Boolean=true, introMessage: Boolean=true) {
		val value = text.toLongOrNull(base)
		
		if (value == null) {
			if (failMessage) {
				session.sendMessage(event.channel, "Not a valid number for base $base: $text")			
			}
			return
		}
		
		if (introMessage) {
			session.sendMessage(event.channel, "Interpreting as number with base $base:")
		}
 
		session.sendMessage(event.channel, """
				|Dec: ${value}
				|Hex: ${value.toString(16)}
				|Bin: ${value.toString(2)}
				""".trimMargin())
	}
		
	private fun respondSearchTranslations(event: SlackMessagePosted, text: String, failMessage: Boolean=true) {
		if (text == "") {
			if (failMessage) {
				session.sendMessage(event.channel, "Nothing to translate.")
			}
			return
		}
		
		val perfectResults = mutableSetOf<Translation>()
		val partialResults = mutableSetOf<Translation>()
		for(translation in translations) {
			if (translation.english.equals(text, ignoreCase=true)) {
				perfectResults.add(translation)
			}
			if (translation.german.equals(text, ignoreCase=true)) {
				perfectResults.add(translation)
			}
			if (translation.english.contains(text, ignoreCase=true)) {
				partialResults.add(translation)
			}
			if (translation.german.contains(text, ignoreCase=true)) {
				partialResults.add(translation)
			}
		}

		var message: String
		if (perfectResults.size > 0) {
			val translations = plural(perfectResults.size, "translation", "translations")
			message = "Found ${perfectResults.size} $translations for exactly this term:\n"
			limitedForLoop(10, 0, sortedTranslations(perfectResults), { result ->
				message += "_${result.english}_ : _${result.german}_ \n"
			}, { _ ->
				message += "...)\n"
			})
		} else if (partialResults.size > 0) {
			val translations = plural(partialResults.size, "translation", "translations")
			message = "Found ${partialResults.size} $translations that partially matched this term:\n"
			limitedForLoop(10, 0, sortedTranslations(partialResults), { result ->
				message += "_${result.english}_ : _${result.german}_ \n"
			}, { _ ->
				message += "...\n"
			})
		} else {
			message = "No translations found."
		}
		
		session.sendMessage(event.channel, message)
	}
	
	private fun sortedTranslations(collection: Collection<Translation>) : List<Translation> {
		val list: MutableList<Translation> = mutableListOf()
		list.addAll(collection)
		return list.sortedWith(compareBy({ it.english.length }, { it.german.length }, { it.english }, { it.german }))
	}
	
	private fun connected(s: SlackSession): SlackSession {
		s.connect()
		return s
	}
}

fun loadProperties(name: String): Properties {
	val properties = Properties()

    //val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("name")
	BufferedReader(FileReader(name)).use {
		properties.load(it)
	}

	return properties
}

fun plural(count: Int, singular: String, plural: String): String {
	if (count == 1) {
		return singular
	} else {
		return plural
	}
}

fun SlackUser.tag() = "<@" + this.id + ">" 

fun SlackSession.user(): SlackUser {
	val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

	val params = HashMap<String, String>()
	val replyHandle = this.postGenericSlackCommand(params, "auth.test")
	val reply = replyHandle.reply.plainAnswer
	
	val response = gson.fromJson(reply, AuthTestResponse::class.java)
	if (!response.ok) {
		throw SlackException(response.error)
	}
	
	return this.findUserById(response.userId)
}

private class AuthTestResponse {
	var ok: Boolean = false
	var error: String = ""
	var warning: String = ""
	var userId: String = ""
	var user: String = ""
	var teamId: String = ""
	var team: String = ""
}

fun main(args: Array<String>) {
	val bot = SimpleBot()
	bot.start()
}
