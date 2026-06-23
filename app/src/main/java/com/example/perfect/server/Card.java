package com.example.perfect.server;

import org.json.JSONObject;

public class Card {
    public int id;
    public String val;
    public String suit;
    public String color;

    public Card(int id, String val, String suit, String color) {
        this.id = id;
        this.val = val;
        this.suit = suit;
        this.color = color;
    }

    public JSONObject toJson() throws org.json.JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("val", val);
        obj.put("suit", suit);
        obj.put("color", color);
        return obj;
    }
}
