package Services;

import Repository.GetTopicByCatRepository;
import java.util.List;
import models.Topic;

public class TopicByCatService {

    private GetTopicByCatRepository repository =
            new GetTopicByCatRepository();

    public List<Topic> getTopicsByCategoryId(int categoryId)
            throws Exception {

        return repository.getTopicsByCategoryId(categoryId);
    }
}