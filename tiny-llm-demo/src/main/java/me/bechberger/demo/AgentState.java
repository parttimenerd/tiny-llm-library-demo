package me.bechberger.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable agent state: goal, plan, and a dynamic TODO list.
 * Rendered as a pinned context block injected into the conversation before every LLM call.
 */
public class AgentState {

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED;

        public String symbol() {
            return switch (this) {
                case PENDING    -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED  -> "[x]";
            };
        }
    }

    public record Todo(int id, String description, Status status) {
        public Todo withStatus(Status s) { return new Todo(id, description, s); }
    }

    private String goal = "";
    private String plan = "";
    private final List<Todo> todos = new ArrayList<>();
    private int nextId = 1;

    public String getGoal()        { return goal; }
    public String getPlan()        { return plan; }
    public List<Todo> getTodos()   { return List.copyOf(todos); }

    public void setGoal(String goal) { this.goal = goal; }
    public void setPlan(String plan) { this.plan = plan; }

    public int addTodo(String description) {
        int id = nextId++;
        todos.add(new Todo(id, description, Status.PENDING));
        return id;
    }

    public boolean updateTodo(int id, Status status) {
        for (int i = 0; i < todos.size(); i++) {
            if (todos.get(i).id() == id) {
                todos.set(i, todos.get(i).withStatus(status));
                return true;
            }
        }
        return false;
    }

    public boolean removeTodo(int id) {
        return todos.removeIf(t -> t.id() == id);
    }

    /** Discard goal, plan and all TODOs — e.g. after the user rejects a proposed plan. */
    public void clear() {
        goal = "";
        plan = "";
        todos.clear();
        nextId = 1;
    }

    public boolean isEmpty() {
        return goal.isBlank() && plan.isBlank() && todos.isEmpty();
    }

    /** Renders the current state as a compact assistant message for injection into the context. */
    public String render() {
        var sb = new StringBuilder();
        if (!goal.isBlank()) {
            sb.append("## Goal\n").append(goal).append("\n\n");
        }
        if (!plan.isBlank()) {
            sb.append("## Plan\n").append(plan).append("\n\n");
        }
        if (!todos.isEmpty()) {
            sb.append("## TODOs\n");
            for (var t : todos) {
                sb.append(t.status().symbol()).append(" #").append(t.id())
                  .append(" ").append(t.description()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
