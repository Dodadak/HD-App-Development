package com.example.evbizassistant.charge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 충전 기록을 앱 내부 파일(charge_sessions.json)에 저장하고 불러온다.
 * 파일 내용은 충전 기록 목록을 JSON 배열로 쓴 것이다.
 */
class ChargeStore(context: Context) {

    private val file = File(context.filesDir, "charge_sessions.json")

    /** 저장된 기록을 모두 불러온다. 파일이 없거나 읽기에 실패하면 빈 목록. */
    fun loadAll(): List<ChargeSession> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> sessionFromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.w(TAG, "충전 기록 불러오기 실패", e)
            emptyList()
        }
    }

    /** 기록 1건 저장. 같은 id가 이미 있으면 새 내용으로 바꾼다 (중복 저장 방지). */
    fun save(session: ChargeSession) {
        val others = loadAll().filter { it.id != session.id }
        writeAll(others + session)
    }

    /** id로 기록 1건 삭제 */
    fun delete(id: String) {
        writeAll(loadAll().filter { it.id != id })
    }

    /** 모든 기록 삭제 */
    fun deleteAll() {
        writeAll(emptyList())
    }

    private fun writeAll(sessions: List<ChargeSession>) {
        try {
            val array = JSONArray()
            sessions.forEach { array.put(sessionToJson(it)) }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "충전 기록 저장 실패", e)
        }
    }

    private fun sessionToJson(s: ChargeSession): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("startTimeMs", s.startTimeMs)
        put("endTimeMs", s.endTimeMs)
        put("startPercent", s.startPercent)
        put("endPercent", s.endPercent)
        put("latitude", s.latitude ?: JSONObject.NULL)
        put("longitude", s.longitude ?: JSONObject.NULL)
        put("isDemo", s.isDemo)
    }

    private fun sessionFromJson(o: JSONObject): ChargeSession = ChargeSession(
        id = o.getString("id"),
        startTimeMs = o.getLong("startTimeMs"),
        endTimeMs = o.getLong("endTimeMs"),
        startPercent = o.getDouble("startPercent"),
        endPercent = o.getDouble("endPercent"),
        latitude = if (o.isNull("latitude")) null else o.getDouble("latitude"),
        longitude = if (o.isNull("longitude")) null else o.getDouble("longitude"),
        isDemo = o.getBoolean("isDemo")
    )

    private companion object {
        const val TAG = "EvCharge"
    }
}