package com.datmt.agent;

public class Helpers {
    public static Integer fromString(String string, int defaultValue) {
       if (string == null || string.trim().isEmpty()) {
           return defaultValue;
       }

       try {
           return Integer.parseInt(string);
       } catch (NumberFormatException e) {
           return defaultValue;
       }
    }
}
