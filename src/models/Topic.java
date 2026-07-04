package models;

public class Topic {

    public int id;
    public String name;
    public int sort_order;
    public boolean is_interview;

    public Topic(int id, String name, int sort_order) {
        this.id = id;
        this.name = name;
        this.sort_order = sort_order;
        this.is_interview = false;
    }

    public Topic(int id, String name, int sort_order, boolean is_interview) {
        this.id = id;
        this.name = name;
        this.sort_order = sort_order;
        this.is_interview = is_interview;
    }
}