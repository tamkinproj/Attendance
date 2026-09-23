package com.muslimedu.attendance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muslimedu.attendance.data.db.entities.StudentEntity

@Dao
interface StudentDao {

    /**
     * Deliberately NOT scoped to one school: `rfid_card_number` is a
     * table-wide UNIQUE index, so the "is this card already taken?" check in
     * [com.muslimedu.attendance.data.repository.StudentRepository.assignRfidCard]
     * has to see every row - scoping it would let a card already assigned in
     * another school slip past the friendly collision message and fail as a
     * raw SQLite constraint violation instead. Scan-time lookups use
     * [findByRfidInSchool].
     */
    @Query("SELECT * FROM students WHERE rfid_card_number = :rfidUid AND is_active = 1 LIMIT 1")
    suspend fun findByRfid(rfidUid: String): StudentEntity?

    /** Scan-time card lookup, scoped so a card cached for a different school can't be scanned into this account's attendance. */
    @Query("SELECT * FROM students WHERE school_id = :schoolId AND rfid_card_number = :rfidUid AND is_active = 1 LIMIT 1")
    suspend fun findByRfidInSchool(schoolId: Int, rfidUid: String): StudentEntity?

    @Query("SELECT * FROM students ORDER BY name ASC")
    suspend fun getAll(): List<StudentEntity>

    /** The logged-in account's own roster - see [findByRfidInSchool] for why device-wide reads aren't what screens want. */
    @Query("SELECT * FROM students WHERE school_id = :schoolId ORDER BY name ASC")
    suspend fun getAllForSchool(schoolId: Int): List<StudentEntity>

    @Query("SELECT COUNT(*) FROM students")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM students WHERE school_id = :schoolId")
    suspend fun countForSchool(schoolId: Int): Int

    @Query("SELECT COUNT(*) FROM students WHERE school_id = :schoolId AND rfid_card_number IS NOT NULL")
    suspend fun countWithRfidForSchool(schoolId: Int): Int

    @Query("SELECT * FROM students WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): StudentEntity?

    /**
     * Looked up before every roster-sync upsert so an already-assigned RFID
     * card (and the row's own id) can be carried forward - the server has no
     * `rfid_card_number` field to send back, so blindly inserting what the
     * roster returns would silently erase any card already assigned to this
     * student. See RosterDto.kt's doc comment on RosterData.
     */
    @Query("SELECT * FROM students WHERE school_id = :schoolId AND student_id = :studentId LIMIT 1")
    suspend fun findBySchoolAndStudentId(schoolId: Int, studentId: Int): StudentEntity?

    /** Lowest [StudentEntity.studentId] in the table, or null if empty - used to pick the next negative id for a locally-added student. */
    @Query("SELECT MIN(student_id) FROM students")
    suspend fun minStudentId(): Int?

    /** A single class's roster for [com.muslimedu.attendance.ui.screens.dashboard.ClassRosterScreen] - the active class's section, cached locally by the last roster sync. */
    @Query("SELECT * FROM students WHERE school_id = :schoolId AND section_id = :sectionId ORDER BY name ASC")
    suspend fun findBySchoolAndSection(schoolId: Int, sectionId: Int): List<StudentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(student: StudentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(students: List<StudentEntity>)

    @Query(
        "UPDATE students SET rfid_card_number = :rfidUid, updated_at = :updatedAt " +
            "WHERE school_id = :schoolId AND student_id = :studentId",
    )
    suspend fun updateRfidCardNumber(schoolId: Int, studentId: Int, rfidUid: String, updatedAt: Long)

    /**
     * One-time backfill for [StudentRepository.seedSampleDataIfEmpty]'s demo
     * rows on an install where they were already seeded before `isLocalOnly`
     * was added to them - a fresh seed would get the flag from the entity
     * default now, but `seedSampleDataIfEmpty` only ever runs once (guarded
     * by `count() > 0`), so an existing install's rows would otherwise stay
     * wrong forever. Idempotent - a no-op once already corrected.
     */
    @Query("UPDATE students SET is_local_only = 1 WHERE code IN (:codes) AND is_local_only = 0")
    suspend fun markLocalOnlyByCode(codes: List<String>)

    @Query("SELECT * FROM students WHERE school_id = :schoolId AND code = :code AND is_active = 1 LIMIT 1")
    suspend fun findByCodeInSchool(schoolId: Int, code: String): StudentEntity?

    @Query("UPDATE students SET school_id = :toSchoolId WHERE school_id = :fromSchoolId")
    suspend fun moveToSchool(fromSchoolId: Int, toSchoolId: Int)
}
