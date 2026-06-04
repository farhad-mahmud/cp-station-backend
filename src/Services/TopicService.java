package Services;

import Repository.GetTopicRepository;
import java.util.List;

public class TopicService {

    private GetTopicRepository trp =
            new GetTopicRepository();

    public List<String> getAllTopics()
            throws Exception {

        return trp.getAllTopics();
    }
}