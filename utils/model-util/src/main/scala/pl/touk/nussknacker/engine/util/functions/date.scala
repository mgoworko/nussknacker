package pl.touk.nussknacker.engine.util.functions

import pl.touk.nussknacker.engine.api.Documentation

import java.time._
import java.time.temporal.Temporal

object date extends DateUtils(Clock.systemDefaultZone())

class DateUtils(clock: Clock) {

  // NOW variations

  @Documentation(description = "Returns current time as an Instant")
  def now(): Instant = Instant.now(clock)

  // We can't has one now() method with multiple overloaded options because ZoneOffset extends ZoneId and we can get
  // unexpected return type during runtime
  @Documentation(description = "Returns current time at given time zone as a ZonedDateTime")
  def nowAtZone(zoneId: ZoneId): ZonedDateTime = now().atZone(zoneId)

  @Documentation(description = "Returns current time with given time zone offset as an OffsetDateTime")
  def nowAtOffset(zoneOffset: ZoneOffset): OffsetDateTime = now().atOffset(zoneOffset)

  @Documentation(description = "Returns current time at default time zone as a ZonedDateTime")
  def nowAtDefaultTimeZone(): ZonedDateTime = now().atZone(clock.getZone)

  // conversions

  @Documentation(description = "Converts ZonedDateTime into epoch (millis from 1970-01-01)")
  def toEpochMilli(zoned: ZonedDateTime): Long = zoned.toInstant.toEpochMilli

  @Documentation(description = "Converts OffsetDateTime into epoch (millis from 1970-01-01)")
  def toEpochMilli(offset: OffsetDateTime): Long = offset.toInstant.toEpochMilli

  @Documentation(description = "Converts LocalDateTime at given time zone into epoch (millis from 1970-01-01)")
  def toEpochMilli(offset: LocalDateTime, zone: ZoneId): Long = offset.atZone(zone).toInstant.toEpochMilli

  @Documentation(description = "Converts LocalDateTime with given time zone offset into epoch (millis from 1970-01-01)")
  def toEpochMilli(offset: LocalDateTime, zoneOffset: ZoneOffset): Long = offset.atOffset(zoneOffset).toInstant.toEpochMilli

  @Documentation(description = "Converts epoch (millis from 1970-01-01) into an Instant")
  def toInstant(timestampMillis: Long): Instant = Instant.ofEpochMilli(timestampMillis)

  @Documentation(description = "Converts LocalDateTime at default time zone into an Instant")
  def toInstantAtDefaultTimeZone(localDateTime: LocalDateTime): Instant = localDateTime.atZone(clock.getZone).toInstant

  @Documentation(description = "Returns LocalDateTime based on LocalDate and LocalTime")
  def localDateTime(date: LocalDate, time: LocalTime): LocalDateTime = LocalDateTime.of(date, time)

  // zone/offset

  @Documentation(description = "Returns time zone with given zone id e.g. UTC, Europe/Warsaw")
  def zone(zoneId: String): ZoneId = ZoneId.of(zoneId)

  @Documentation(description = "Returns zone offset with given zone offset id e.g. Z, +01:00")
  def zoneOffset(offsetId: String): ZoneOffset = ZoneOffset.of(offsetId)

  @Documentation(description = "Returns Zulu time zone which has offset always equals to UTC+0")
  def zuluTimeZone(): ZoneId = ZoneId.of("Z")

  @Documentation(description = "Returns UTC time zone offset")
  def UTCOffset(): ZoneOffset = ZoneOffset.UTC

  @Documentation(description = "Returns default time zone")
  def defaultTimeZone(): ZoneId = clock.getZone

  def MONDAY: DayOfWeek = DayOfWeek.MONDAY
  def TUESDAY: DayOfWeek = DayOfWeek.TUESDAY
  def WEDNESDAY: DayOfWeek = DayOfWeek.WEDNESDAY
  def THURSDAY: DayOfWeek = DayOfWeek.THURSDAY
  def FRIDAY: DayOfWeek = DayOfWeek.FRIDAY
  def SATURDAY: DayOfWeek = DayOfWeek.SATURDAY
  def SUNDAY: DayOfWeek = DayOfWeek.SUNDAY

  @Documentation(description = "Computes Period between two dates: start date inclusive and end date exclusive")
  def periodBetween(startDateInclusive: LocalDate, endDateExclusive: LocalDate): Period = Period.between(startDateInclusive, endDateExclusive)

  @Documentation(description = "Computes Period between two dates: start date inclusive and end date exclusive")
  def periodBetween(startDateInclusive: ZonedDateTime, endDateExclusive: ZonedDateTime): Period = Period.between(startDateInclusive.toLocalDate, endDateExclusive.toLocalDate)

  @Documentation(description = "Computes Period between two dates: start date inclusive and end date exclusive")
  def periodBetween(startDateInclusive: OffsetDateTime, endDateExclusive: OffsetDateTime): Period = Period.between(startDateInclusive.toLocalDate, endDateExclusive.toLocalDate)

  @Documentation(description = "Computes Duration between two dates: start date inclusive and end date exclusive")
  def durationBetween(startDateInclusive: Temporal, endDateExclusive: Temporal): Duration = Duration.between(startDateInclusive, endDateExclusive)

  @Documentation(description = "Checks if time is in range <fromInclusive, toInclusive>. if to < from, then checks if time is in one of ranges <from, 24:00> and <00:00, to>")
  def isBetween(time: LocalTime, fromInclusive: LocalTime, toInclusive: LocalTime): Boolean = {
    if (!toInclusive.isBefore(fromInclusive)) { // normal range (from <= to) e.g. 09:00 - 17:00
      !time.isBefore(fromInclusive) && !time.isAfter(toInclusive)
    } else { // range across midnight (to < from) e.g. 22:00 - 05:00
      !time.isBefore(fromInclusive) || !time.isAfter(toInclusive)
    }
  }

  @Documentation(description = "Checks if day of week is in range <fromInclusive, toInclusive>. if to < from in ISO standard (numerous from MONDAY), then checks if day of week is in one of ranges <from, SUNDAY> and <MONDAY, to>")
  def isBetween(dayOfWeek: DayOfWeek, fromInclusive: DayOfWeek, toInclusive: DayOfWeek): Boolean = {
    val value = dayOfWeek.getValue
    val from = fromInclusive.getValue
    val to = toInclusive.getValue
    if (from <= to) {
      value >= from && value <= to
    } else {
      value >= from || value <= to
    }
  }

  @Documentation(description = "Checks if day is in range <fromInclusive, toInclusive>.")
  def isBetween(date: LocalDate, fromInclusive: LocalDate, toInclusive: LocalDate): Boolean = {
    !date.isBefore(fromInclusive) && !date.isAfter(toInclusive)
  }

  @Documentation(description = "Checks if day is in range <fromInclusive, toInclusive>.")
  def isBetween(date: LocalDateTime, fromInclusive: LocalDateTime, toInclusive: LocalDateTime): Boolean = {
    !date.isBefore(fromInclusive) && !date.isAfter(toInclusive)
  }

}