package models;

public class Topic {

    public int id;
    public String name;
    public int sort_order;

    public Topic(int id, String name, int sort_order) {
        this.id = id;
        this.name = name;
        this.sort_order = sort_order;
    }
}