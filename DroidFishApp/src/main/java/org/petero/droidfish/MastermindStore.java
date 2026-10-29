package org.petero.droidfish;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.petero.droidfish.gamelogic.MastermindCoach;

final class MastermindStore {
    private final SharedPreferences preferences;

    MastermindStore(Context context) {
        preferences = context.getSharedPreferences("mastermind_training", Context.MODE_PRIVATE);
    }

    private JSONArray read(String key) {
        try {
            JSONArray saved = new JSONArray(preferences.getString(key, "[]"));
            JSONArray valid = new JSONArray();
            for (int index = 0; index < saved.length(); index++)
                if (saved.optJSONObject(index) != null)
                    valid.put(saved.optJSONObject(index));
            return valid;
        } catch (JSONException exception) {
            return new JSONArray();
        }
    }

    boolean record(MastermindCoach.Mistake mistake) {
        JSONArray saved = read("mistakes");
        for (int index = 0; index < saved.length(); index++)
            if (mistake.key.equals(saved.optJSONObject(index).optString("key")))
                return false;
        try {
            JSONObject item = new JSONObject();
            item.put("key", mistake.key);
            item.put("category", mistake.category);
            item.put("move", mistake.move);
            item.put("recommendation", mistake.recommendation);
            item.put("loss", mistake.loss);
            saved.put(item);
            save("mistakes", saved, 500);
            return true;
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String summary() {
        JSONArray saved = read("mistakes");
        if (saved.length() == 0)
            return "No reviewed mistakes saved.";
        Map<String, Integer> counts = new TreeMap<>();
        for (int index = 0; index < saved.length(); index++) {
            String category = saved.optJSONObject(index).optString("category");
            Integer count = counts.get(category);
            counts.put(category, count == null ? 1 : count + 1);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        text.append("\nRecent findings\n");
        for (int index = saved.length() - 1; index >= Math.max(0, saved.length() - 10); index--) {
            JSONObject item = saved.optJSONObject(index);
            text.append(item.optString("move")).append(" instead of ")
                .append(item.optString("recommendation")).append(": ")
                .append(MastermindCoach.lossText(item.optInt("loss"))).append(" pawn loss\n");
        }
        text.append("\nEngine estimates, not definitive diagnoses. Counts are unique reviewed positions, grouped by the kind of move played.");
        return text.toString();
    }

    int addDrills(List<MastermindCoach.Drill> drills) {
        JSONArray saved = read("drills");
        int added = 0;
        for (MastermindCoach.Drill drill : drills) {
            boolean exists = false;
            for (int index = 0; index < saved.length(); index++) {
                JSONObject item = saved.optJSONObject(index);
                if (drill.fen.equals(item.optString("fen")) && drill.answer.equals(item.optString("answer"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try {
                    JSONObject item = new JSONObject();
                    item.put("fen", drill.fen);
                    item.put("answer", drill.answer);
                    saved.put(item);
                    added++;
                } catch (JSONException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
        save("drills", saved, 240);
        return added;
    }

    List<MastermindCoach.Drill> drills() {
        List<MastermindCoach.Drill> drills = new ArrayList<>();
        JSONArray saved = read("drills");
        for (int index = 0; index < saved.length(); index++) {
            JSONObject item = saved.optJSONObject(index);
            drills.add(new MastermindCoach.Drill(item.optString("fen"), item.optString("answer")));
        }
        return drills;
    }

    private void save(String key, JSONArray values, int limit) {
        JSONArray bounded = new JSONArray();
        for (int index = Math.max(0, values.length() - limit); index < values.length(); index++)
            bounded.put(values.opt(index));
        preferences.edit().putString(key, bounded.toString()).apply();
    }

    void clear() {
        preferences.edit().clear().apply();
    }
}