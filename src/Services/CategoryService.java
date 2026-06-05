package Services;
import models.Category;
import Repository.GetCatRepository;

import java.util.List;


public class CategoryService {

    private GetCatRepository get_cat =
            new GetCatRepository();

    public List<Category> getAllCategories() throws Exception {
        return get_cat.getAllCategories();
    }
}