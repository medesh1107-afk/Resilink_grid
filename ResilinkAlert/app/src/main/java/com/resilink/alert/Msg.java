package com.resilink.alert;

/** Names, colours and the words shown for each alert. Edit the text here. */
final class Msg {
    private Msg() {}

    // Villages the user can pick. Letters and the node ids used in the firmware.
    static final String[] LETTERS = {"A", "B", "C", "D"};
    static final int[] IDS = {2, 3, 4, 5};

    static final int GREEN = 0, RED = 1, YELLOW = 2;

    static String nodeName(int id) {
        switch (id) {
            case 1: return "City control";
            case 2: return "Village A";
            case 3: return "Village B";
            case 4: return "Village C";
            case 5: return "Village D";
            default: return "node " + id;
        }
    }

    static String letterOf(int id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i] == id) return LETTERS[i];
        return "?";
    }

    /** kind: 1 DANGER, 2 EVACUATE, 3 HELP NEEDED, 4 ATTENTION, 0 none. down: id of a node that is down, 0 none. */
    static boolean active(int kind, int down) {
        return (kind >= 1 && kind <= 4) || down != 0;
    }

    /** Same colours as the bulbs: red = danger or evacuate, yellow = help or node down, green = calm. */
    static int colour(int kind, int down) {
        if (kind == 1 || kind == 2) return RED;
        if (kind == 3 || kind == 4 || down != 0) return YELLOW;
        return GREEN;
    }

    static String title(int kind, int down) {
        if (kind == 2) return "EVACUATE NOW";
        if (kind == 1) return "DANGER NEARBY";
        if (kind == 3) return "HELP NEEDED";
        if (kind == 4) return "ATTENTION";
        if (down != 0) return "NODE DOWN";
        return "ALL CLEAR";
    }

    static String body(int kind, int origin, int down) {
        StringBuilder sb = new StringBuilder();
        if (kind == 2) {
            sb.append("Emergency evacuation. Alert raised by ").append(nodeName(origin)).append(".");
            if (origin == 2) sb.append(" Rock movement was detected on the hill.");
            sb.append(" Leave the area now and go to the nearest safe high ground. ")
              .append("Do not go back for belongings. Help children and elders.");
        } else if (kind == 1) {
            sb.append("Danger alert raised by ").append(nodeName(origin)).append(". ")
              .append("Stay alert, keep away from the hazard area and be ready to move.");
        } else if (kind == 3) {
            sb.append("Someone at ").append(nodeName(origin)).append(" needs help. ")
              .append("If you are close, go to them. City control has been told.");
        } else if (kind == 4) {
            sb.append("Attention notice from ").append(nodeName(origin)).append(". Stay alert and watch for updates.");
        } else if (down != 0) {
            sb.append(nodeName(down)).append(" has stopped responding. ")
              .append("The network is rerouting around it and City control has opened an inspection ticket. Stay alert.");
            return sb.toString();
        } else {
            return "No alerts right now.";
        }
        if (down != 0) {
            sb.append("\n\n").append(nodeName(down)).append(" has also stopped responding.");
        }
        return sb.toString();
    }
}
