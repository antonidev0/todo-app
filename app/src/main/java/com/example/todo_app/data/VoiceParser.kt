package com.example.todo_app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Lo que se ha entendido de una frase dicha en voz alta.
 *
 * @property form título, día, hora, repetición, minutos y prioridad, listo para guardar.
 * @property subtasks títulos de las subtareas, en orden (vacío si no se dijo ninguna).
 * @property hasDay true si en la frase había un día ("mañana", "el lunes"...) o una hora.
 *   Si es false, quien la guarda puede ponerla en el día que se está viendo.
 */
data class VoiceTask(
    val form: TaskForm,
    val subtasks: List<String> = emptyList(),
    val hasDay: Boolean = false,
)

/**
 * Entiende frases en español como «recuérdame comprar pan mañana a las 9» o
 * «todos los lunes a las 8 sacar la basura» y las convierte en una tarea.
 * Funciona sin internet: son reglas, no inteligencia artificial, así que entiende
 * las formas más habituales de decir día, hora, repetición, prioridad y subtareas.
 *
 * Lo que no reconoce se queda en el título, así que nunca se pierde nada de lo dicho.
 */
object VoiceParser {

    /** Devuelve null si después de quitar día, hora, etc. no queda nada que hacer. */
    fun parse(
        text: String,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): VoiceTask? {
        val p = Parse(text.replace('\n', ' '))
        p.readRepeat()
        p.readRelative()
        p.readDuration()
        p.readTime()
        p.readPeriodAlone()
        p.readDate(now.toLocalDate())
        p.readPriority()
        val subtasks = p.readSubtasks()
        val title = cleanTitle(p.rest) ?: return null

        val today = now.toLocalDate()
        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        val time = p.time()
        var date: LocalDate? = p.date ?: p.dayOffset?.let { today.plusDays(it) }
        var reminderAt: Long? = null

        when {
            p.repeatDays != 0 -> {
                date = null
                if (time != null) {
                    reminderAt = Recurrence.next(nowMillis, p.repeatDays, time.first, time.second, zone)
                }
            }
            p.relativeMinutes != null -> {
                val at = now.plusMinutes(p.relativeMinutes!!.toLong())
                reminderAt = at.atZone(zone).toInstant().toEpochMilli()
                date = at.toLocalDate()
            }
            time != null -> {
                var at = (date ?: today).atTime(time.first, time.second)
                // «A las 9» dicho a las 15:00 y sin día: se entiende que es mañana.
                if (date == null && !at.isAfter(now)) at = at.plusDays(1)
                reminderAt = at.atZone(zone).toInstant().toEpochMilli()
                date = at.toLocalDate()
            }
        }

        return VoiceTask(
            form = TaskForm(
                title = title,
                reminderAt = reminderAt,
                repeatDays = p.repeatDays,
                minutes = p.minutes,
                priority = p.priority,
                date = date?.toString(),
            ),
            subtasks = subtasks,
            hasDay = p.repeatDays != 0 || date != null,
        )
    }

    // ---- Piezas de las expresiones ----

    /** Límites de palabra que funcionan con ñ y tildes (\b solo entiende ASCII). */
    private const val B0 = "(?<![\\p{L}\\d])"
    private const val B1 = "(?![\\p{L}\\d])"

    private val numberWords = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11,
        "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15, "dieciséis" to 16,
        "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40,
        "cuarenta y cinco" to 45, "cincuenta" to 50, "noventa" to 90,
    )

    /** Un número en cifras o en palabras (las más largas primero para que ganen). */
    private val NUM = "(\\d{1,3}|" + numberWords.keys.sortedByDescending { it.length }.joinToString("|") + ")"

    private fun number(s: String?): Int? {
        if (s == null) return null
        val t = s.lowercase().trim()
        return t.toIntOrNull() ?: numberWords[t]
    }

    private const val DAY = "(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bados?|domingos?)"

    private fun dayOf(word: String): DayOfWeek = when (word.lowercase().take(2)) {
        "lu" -> DayOfWeek.MONDAY
        "ma" -> DayOfWeek.TUESDAY
        "mi" -> DayOfWeek.WEDNESDAY
        "ju" -> DayOfWeek.THURSDAY
        "vi" -> DayOfWeek.FRIDAY
        "sá", "sa" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private val months = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val MONTH = "(" + months.joinToString("|") + "|setiembre)"

    private const val PERIOD =
        "(de la ma[nñ]ana|de la madrugada|de la tarde|de la noche|por la ma[nñ]ana|por la tarde|por la noche|" +
            "a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)"

    private enum class Period { MORNING, EARLY, AFTERNOON, NIGHT, AM, PM }

    private fun periodOf(s: String): Period {
        val t = s.lowercase().replace(Regex("[.\\s]"), "")
        return when {
            t == "am" -> Period.AM
            t == "pm" -> Period.PM
            t.endsWith("madrugada") -> Period.EARLY
            t.endsWith("tarde") -> Period.AFTERNOON
            t.endsWith("noche") -> Period.NIGHT
            else -> Period.MORNING
        }
    }

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    // ---- El análisis, paso a paso ----

    private class Parse(var rest: String) {
        var repeatDays = 0
        var relativeMinutes: Int? = null
        var minutes: Int? = null
        var hour: Int? = null
        var minute = 0
        var period: Period? = null
        var date: LocalDate? = null
        var dayOffset: Long? = null
        var priority = Priority.NORMAL

        /** Busca [regex], lo quita del texto y devuelve lo encontrado. */
        fun take(regex: Regex): MatchResult? {
            val m = regex.find(rest) ?: return null
            rest = rest.substring(0, m.range.first) + " " + rest.substring(m.range.last + 1)
            return m
        }

        fun readRepeat() {
            when {
                take(rx("${B0}(todos los d[ií]as|cada d[ií]a|a diario|diariamente)$B1")) != null ->
                    repeatDays = 0b1111111
                take(rx("${B0}((todos )?los )?(d[ií]as laborables|de lunes a viernes|entre semana)$B1")) != null ->
                    repeatDays = 0b0011111
                take(rx("${B0}((todos )?los fines de semana|cada fin de semana|los findes)$B1")) != null ->
                    repeatDays = 0b1100000
                else -> {
                    // «todos los lunes», «cada martes y jueves», «los lunes, miércoles y viernes».
                    val list = "$DAY(?:\\s*,\\s*|\\s+y\\s+|\\s+e\\s+|\\s+)?"
                    val m = take(rx("${B0}(?:todos los|cada|los)\\s+((?:$list)+)$B1")) ?: return
                    val days = Regex(DAY, RegexOption.IGNORE_CASE).findAll(m.groupValues[1])
                        .map { dayOf(it.value) }.toList()
                    // «los lunes» en plural es repetición; «el lunes» se lee después como un día.
                    repeatDays = days.fold(0) { acc, d -> acc or Recurrence.bit(d) }
                }
            }
        }

        /** «en 20 minutos», «dentro de 2 horas», «en media hora». */
        fun readRelative() {
            val m = take(rx("${B0}(?:dentro de|en)\\s+$NUM\\s+(minutos?|min|horas?)(\\s+y media)?$B1"))
            if (m != null) {
                val n = number(m.groupValues[1]) ?: return
                val isHour = m.groupValues[2].lowercase().startsWith("h")
                relativeMinutes = (if (isHour) n * 60 else n) + (if (m.groupValues[3].isNotEmpty()) 30 else 0)
                return
            }
            if (take(rx("${B0}(?:dentro de|en)\\s+media hora$B1")) != null) relativeMinutes = 30
        }

        /** «que dura 30 minutos», «de una hora», «tarda media hora». */
        fun readDuration() {
            val verb = "(?:(?:que\\s+)?(?:dura|durará|tarda|tardo|lleva|me lleva|son)|durante|de|unos|unas)"
            if (take(rx("${B0}$verb\\s+(?:una\\s+)?media hora$B1")) != null) {
                minutes = 30
                return
            }
            val m = take(rx("${B0}$verb\\s+$NUM\\s+(minutos?|min|horas?)(\\s+y media)?$B1")) ?: return
            val n = number(m.groupValues[1]) ?: return
            val isHour = m.groupValues[2].lowercase().startsWith("h")
            minutes = (if (isHour) n * 60 else n) + (if (m.groupValues[3].isNotEmpty()) 30 else 0)
        }

        /** «a las 9», «a las 9 y media», «a las 21:30», «a la una de la tarde», «a mediodía». */
        fun readTime() {
            if (take(rx("${B0}(?:para\\s+)?(?:a|al|sobre el|hacia el)\\s+mediod[ií]a$B1")) != null) {
                hour = 12
                return
            }
            if (take(rx("${B0}(?:para\\s+)?a\\s+medianoche$B1")) != null) {
                hour = 0
                period = Period.AM
                return
            }
            val m = take(
                rx(
                    "${B0}(?:para\\s+)?(?:a|sobre|hacia|para)\\s+las?\\s+$NUM" +
                        "(?:(?::|\\.|h)(\\d{2})|\\s+y\\s+(media|cuarto|$NUM)|\\s+menos\\s+(cuarto|$NUM))?" +
                        "(?:\\s*(?:h|horas))?(?:\\s+en punto)?(?:\\s*$PERIOD)?(?![\\p{L}\\d])"
                )
            ) ?: run {
                // Una hora con dos puntos suelta: «21:30».
                take(rx("${B0}(\\d{1,2}):(\\d{2})(?:\\s*$PERIOD)?(?![\\p{L}\\d])"))?.let { mm ->
                    setTime(number(mm.groupValues[1]), mm.groupValues[2].toInt(), mm.groupValues[3])
                }
                return
            }
            val g = m.groupValues
            val h = number(g[1])
            var min = 0
            var hh = h
            when {
                g[2].isNotEmpty() -> min = g[2].toInt()
                g[3].isNotEmpty() -> min = when (g[3].lowercase()) {
                    "media" -> 30
                    "cuarto" -> 15
                    else -> number(g[3]) ?: 0
                }
                g[5].isNotEmpty() -> {
                    val less = if (g[5].lowercase() == "cuarto") 15 else number(g[5]) ?: 0
                    hh = h?.let { (it + 23) % 24 }
                    min = 60 - less
                }
            }
            setTime(hh, min, g.last())
        }

        private fun setTime(h: Int?, min: Int, periodText: String) {
            if (h == null || h > 23 || min !in 0..59) return
            hour = h
            minute = min
            if (periodText.isNotBlank()) period = periodOf(periodText)
        }

        /** «por la tarde», «esta noche», «de madrugada» sin hora: ayudan a entender la hora. */
        fun readPeriodAlone() {
            take(rx("${B0}esta\\s+(ma[nñ]ana|tarde|noche)$B1"))?.let {
                dayOffset = 0
                if (period == null) period = periodOf(it.groupValues[1])
                return
            }
            take(rx("${B0}(?:por la|de la)\\s+(ma[nñ]ana|tarde|noche)$B1"))?.let {
                if (period == null) period = periodOf(it.groupValues[1])
                return
            }
            take(rx("${B0}de madrugada$B1"))?.let { if (period == null) period = Period.EARLY }
        }

        fun readDate(today: LocalDate) {
            val pre = "(?:para\\s+)?"
            when {
                take(rx("${B0}${pre}pasado\\s+ma[nñ]ana$B1")) != null -> dayOffset = 2
                take(rx("${B0}${pre}ma[nñ]ana$B1")) != null -> dayOffset = 1
                take(rx("${B0}${pre}hoy$B1")) != null -> dayOffset = 0
                take(rx("${B0}${pre}(?:la semana que viene|la pr[oó]xima semana)$B1")) != null ->
                    date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                take(rx("${B0}${pre}(?:este|el)\\s+fin\\s+de\\s+semana$B1")) != null ->
                    date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                        .let { if (today.dayOfWeek == DayOfWeek.SUNDAY) today else it }
                else -> readDayOfMonth(today) || readWeekday(today)
            }
        }

        /** «el 25», «el día 3 de diciembre», «el 1 de enero de 2027». */
        private fun readDayOfMonth(today: LocalDate): Boolean {
            val m = take(
                rx("${B0}(?:para\\s+)?el\\s+(?:d[ií]a\\s+)?(\\d{1,2})(?:\\s+de\\s+$MONTH(?:\\s+(?:de|del)\\s+(\\d{4}))?)?$B1")
            ) ?: return false
            val day = m.groupValues[1].toInt()
            val monthName = m.groupValues[2].lowercase().let { if (it == "setiembre") "septiembre" else it }
            val year = m.groupValues[3].toIntOrNull()
            date = runCatching {
                if (monthName.isEmpty()) {
                    // Sin mes: este mes, o el que viene si ese día ya ha pasado.
                    val thisMonth = today.withDayOfMonth(day)
                    if (thisMonth.isBefore(today)) today.plusMonths(1).withDayOfMonth(day) else thisMonth
                } else {
                    val month = months.indexOf(monthName) + 1
                    val d = LocalDate.of(year ?: today.year, month, day)
                    if (year == null && d.isBefore(today)) d.plusYears(1) else d
                }
            }.getOrNull()
            return true
        }

        /** «el lunes», «este viernes», «el próximo martes», «el jueves que viene». */
        private fun readWeekday(today: LocalDate): Boolean {
            val m = take(
                rx("${B0}(?:para\\s+)?(?:el|este)\\s+(?:pr[oó]ximo\\s+)?$DAY(?:\\s+que viene|\\s+pr[oó]ximo)?$B1")
            ) ?: return false
            date = today.with(TemporalAdjusters.next(dayOf(m.groupValues[1])))
            return true
        }

        fun readPriority() {
            when {
                take(rx("${B0}(?:es\\s+)?(?:muy\\s+)?(?:urgente|importante|lo primero|primero|con prioridad|prioridad alta)$B1")) != null ->
                    priority = Priority.FIRST
                take(rx("${B0}(?:al final|para el final|lo [uú]ltimo|sin prisa|cuando pueda|prioridad baja)$B1")) != null ->
                    priority = Priority.LAST
            }
        }

        /**
         * «hacer la compra: pan, leche y huevos» o «limpiar la casa con subtareas
         * barrer, fregar y quitar el polvo». Lo de antes es la tarea; lo de después, las subtareas.
         */
        fun readSubtasks(): List<String> {
            val marker = rx(
                "(?:\\s*:\\s*|,?\\s+(?:con\\s+(?:las\\s+)?subtareas|con\\s+(?:los\\s+)?pasos|subtareas|" +
                    "que\\s+(?:incluye|son)|incluyendo|con\\s+lo\\s+siguiente)\\s*:?\\s*)"
            )
            val m = marker.find(rest) ?: return emptyList()
            val list = rest.substring(m.range.last + 1)
            rest = rest.substring(0, m.range.first)
            return list.split(rx("\\s*[,;]\\s*|\\s+y\\s+luego\\s+|\\s+y\\s+despu[eé]s\\s+|\\s+luego\\s+|\\s+despu[eé]s\\s+|\\s+y\\s+|\\s+e\\s+"))
                .mapNotNull { cleanTitle(it, stripCommands = false) }
        }

        /** La hora final en 24 h, teniendo en cuenta «de la tarde» o, si no se dijo, lo más habitual. */
        fun time(): Pair<Int, Int>? {
            val h = hour ?: return null
            val h24 = when (period) {
                Period.AFTERNOON, Period.PM -> if (h < 12) h + 12 else h
                Period.NIGHT -> when {
                    h == 12 -> 0
                    h >= 7 && h < 12 -> h + 12
                    else -> h
                }
                Period.EARLY, Period.AM -> h % 12
                Period.MORNING -> h
                // Sin decir mañana o tarde, «a las 5» casi siempre es por la tarde.
                null -> if (h in 1..6) h + 12 else h
            }
            return h24 to minute
        }
    }

    private val leadingFiller = listOf(
        rx("^(?:oye|vale|bueno|por favor|porfa)$B1[,\\s]*"),
        rx(
            "^(?:recu[eé]rdame|recu[eé]rdeme|recordarme|recordatorio|av[ií]same|recu[eé]rdale\\s+a\\s+\\p{L}+)$B1" +
                "\\s*(?:(?:que|de|para|a)$B1\\s*)?"
        ),
        // «Añade comprar pan»: el verbo es para la app.
        rx(
            "^(?:a[nñ]ade|a[nñ]adir|agrega|agregar|apunta|apuntar|anota|anotar)$B1" +
                "\\s*(?:(?:una|un)\\s+)?(?:(?:tarea|subtarea|recordatorio|nota)$B1)?\\s*(?:(?:de|para|que)$B1\\s*)?:?\\s*"
        ),
        // «Pon una tarea de...», pero no «Poner la lavadora», que es la tarea en sí.
        rx(
            "^(?:pon|poner|crea|crear|mete|meter|nueva|nuevo)?\\s*(?:(?:una|un)\\s+)?" +
                "(?:tarea|subtarea|recordatorio|nota)$B1\\s*(?:(?:de|para|que)$B1\\s*)?:?\\s*"
        ),
        rx("^(?:tengo que|tienes que|tenemos que|hay que|debo|debes|necesito|tendr[eé] que)$B1\\s*"),
        rx("^(?:que|de|para|a|y|e|:)$B1\\s*"),
    )

    private val trailingFiller = rx("[\\s,.;:]*(?:$B0(?:y|e|a|de|del|el|la|los|las|para|que|con|en|sobre|por favor|porfa)$B1)?[\\s,.;:!?¡¿]*$")

    /** Quita lo que no forma parte de la tarea y pone la primera letra en mayúscula. */
    private fun cleanTitle(raw: String, stripCommands: Boolean = true): String? {
        var s = raw.replace(Regex("\\s+"), " ").trim().trim(',', '.', ';', ':', '!', '?', '¡', '¿', ' ')
        var changed = true
        while (changed) {
            val before = s
            if (stripCommands) leadingFiller.forEach { s = it.replace(s, "").trim() }
            else s = leadingFiller.last().replace(s, "").trim()
            s = trailingFiller.replace(s, "").trim()
            changed = s != before
        }
        if (s.isEmpty()) return null
        return s.replaceFirstChar { it.titlecase() }
    }
}
