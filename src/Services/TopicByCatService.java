package Services;

import java.util.List;

import models.Topic;

public class TopicByCatService {
    public List<Topic> getTopicsByCategoryId(int categoryId)
        throws Exception {

    return topicRepository
            .getTopicsByCategoryId(categoryId);
}
}

