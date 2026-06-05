package Services;

import Repository.GetTopicByCatRepository;
import java.util.List;

import models.Topic;

public class TopicByCatService {
    public List<Topic> get_top_by_cat(int categoryId)
        throws Exception {

    return GetTopicByCatRepository
         .get_top_by_cat(categoryId);
}
}

