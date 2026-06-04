package Services;

import Repository.GetCatRepository;

import java.util.List;

public class CategoryService {

    private GetCatRepository get_cat  =
            new GetCatRepository();

    public List<String> getAllTopics()
            throws Exception {

        return get_cat.getAllTopics();
    }
}