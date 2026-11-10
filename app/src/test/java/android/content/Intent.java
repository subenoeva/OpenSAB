package android.content;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    private String action;
    private final Map<String, Object> extras = new HashMap<>();

    public Intent() {}

    public Intent(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }

    public Intent setAction(String action) {
        this.action = action;
        return this;
    }

    public Intent putExtra(String name, String value) {
        extras.put(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        extras.put(name, value);
        return this;
    }

    public String getStringExtra(String name) {
        Object val = extras.get(name);
        return val instanceof String ? (String) val : null;
    }

    public int getIntExtra(String name, int defaultValue) {
        Object val = extras.get(name);
        return val instanceof Integer ? (Integer) val : defaultValue;
    }
}
