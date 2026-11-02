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
    private final SharedPreferences gamePreferences;

    MastermindStore(Context context) {
        preferences = context.getSharedPreferences("mastermind_training", Context.MODE_PRIVATE);
        gamePreferences = context.getSharedPreferences("mastermind_games", Context.MODE_PRIVATE);
    }

    private JSONArray read(String key) {
        return read(preferences, key);
    }

    private JSONArray read(SharedPreferences storage, String key) {
        try {
            JSONArray saved = new JSONArray(storage.getString(key, "[]"));
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
        int existing = -1;
        for (int index = 0; index < saved.length(); index++)
            if (mistake.key.equals(saved.optJSONObject(index).optString("key")))
                existing = index;
        try {
            JSONObject item = new JSONObject();
            item.put("key", mistake.key);
            item.put("category", mistake.category);
            item.put("move", mistake.move);
            item.put("recommendation", mistake.recommendation);
            item.put("loss", mistake.loss);
            item.put("fen", mistake.practice.fen);
            item.put("answer", mistake.practice.answer);
            if (existing < 0)
                saved.put(item);
            else
                saved.put(existing, item);
            save("mistakes", saved, 500);
            return existing < 0;
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    List<MastermindCoach.Drill> mistakeDrills() {
        List<MastermindCoach.Drill> drills = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        JSONArray saved = read("mistakes");
        for (int index = saved.length() - 1; index >= 0; index--) {
            JSONObject item = saved.optJSONObject(index);
            MastermindCoach.Drill drill = new MastermindCoach.Drill(item.optString("fen"), item.optString("answer"));
            try {
                org.petero.droidfish.gamelogic.Position position =
                        org.petero.droidfish.gamelogic.TextIO.readFEN(drill.fen);
                org.petero.droidfish.gamelogic.Move answer =
                        org.petero.droidfish.gamelogic.TextIO.UCIstringToMove(drill.answer);
                if (answer != null && org.petero.droidfish.gamelogic.TextIO.isValid(position, answer) &&
                        seen.add(drill.key()))
                    drills.add(drill);
            } catch (org.petero.droidfish.gamelogic.ChessParseError ignored) {
            }
        }
        return drills;
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

    static final class SavedGame {
        final String id;
        final String title;
        final long savedAt;
        final int mode;
        final int version;
        final byte[] state;

        SavedGame(JSONObject item) throws JSONException {
            id = item.getString("id");
            title = item.getString("title");
            savedAt = item.getLong("savedAt");
            mode = item.getInt("mode");
            version = item.getInt("version");
            state = android.util.Base64.decode(item.getString("state"), android.util.Base64.DEFAULT);
            if (id.isEmpty() || title.isEmpty() || mode < 1 || mode > 6 || version != 4 ||
                    state.length == 0 || state.length > 262144)
                throw new JSONException("Invalid saved game");
        }
    }

    List<SavedGame> games() {
        ArrayList<SavedGame> games = new ArrayList<>();
        JSONArray saved = read(gamePreferences, "games");
        for (int index = saved.length() - 1; index >= 0; index--) {
            try {
                games.add(new SavedGame(saved.getJSONObject(index)));
            } catch (JSONException | IllegalArgumentException ignored) {
            }
        }
        return games;
    }

    String saveGame(String id, String title, int mode, byte[] state) {
        if (state == null || state.length == 0 || state.length > 262144)
            throw new IllegalArgumentException("This game is too large to save, or its state is unavailable.");
        title = title.trim();
        if (title.isEmpty() || title.length() > 80 || mode < 1 || mode > 6)
            throw new IllegalArgumentException("Enter a game name of 1 to 80 characters.");
        JSONArray saved = read(gamePreferences, "games");
        JSONArray updated = new JSONArray();
        for (int index = 0; index < saved.length(); index++) {
            try {
                SavedGame existing = new SavedGame(saved.getJSONObject(index));
                if (!existing.id.equals(id))
                    updated.put(saved.optJSONObject(index));
            } catch (JSONException | IllegalArgumentException ignored) {
            }
        }
        if (updated.length() >= 20)
            throw new IllegalStateException("20 games are already saved. Delete one before saving another.");
        if (id == null)
            id = java.util.UUID.randomUUID().toString();
        try {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("title", title);
            item.put("savedAt", System.currentTimeMillis());
            item.put("mode", mode);
            item.put("version", 4);
            item.put("state", android.util.Base64.encodeToString(state, android.util.Base64.NO_WRAP));
            updated.put(item);
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        if (!gamePreferences.edit().putString("games", updated.toString()).commit())
            throw new IllegalStateException("The game could not be written to storage.");
        return id;
    }

    void deleteGame(String id) {
        JSONArray saved = read(gamePreferences, "games");
        JSONArray updated = new JSONArray();
        for (int index = 0; index < saved.length(); index++)
            if (!id.equals(saved.optJSONObject(index).optString("id")))
                updated.put(saved.optJSONObject(index));
        if (!gamePreferences.edit().putString("games", updated.toString()).commit())
            throw new IllegalStateException("The saved game could not be deleted.");
    }
}