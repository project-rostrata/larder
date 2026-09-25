import com.sun.net.httpserver.HttpServer
import larder.api.IngredientMergeHandler
import larder.api.LoginHandler
import larder.api.MealPlanCreateHandler
import larder.api.MealPlanDeleteHandler
import larder.api.MealPlanListHandler
import larder.api.ShoppingListCreateHandler
import larder.api.ShoppingListDeleteHandler
import larder.api.ShoppingListGetHandler
import larder.api.ShoppingListItemHandlers
import larder.api.ShoppingListsListHandler
import larder.api.LogoutHandler
import larder.api.MeHandler
import larder.api.RecipeCreateHandler
import larder.api.RecipeDeleteHandler
import larder.api.RecipeGetHandler
import larder.api.RecipeImportHandler
import larder.api.RecipeUpdateHandler
import larder.api.RecipesListHandler
import larder.api.RegisterHandler
import larder.api.Router
import larder.api.StaticFileHandler
import larder.api.VersionHandler
import larder.api.healthHandler
import larder.api.requireAuth
import larder.auth.AuthConfig
import larder.db.ConnectionPool
import larder.db.Database
import larder.db.IngredientRepository
import larder.db.MealPlanRepository
import larder.db.ShoppingListRepository
import larder.db.MigrationRunner
import larder.db.RecipeRepository
import larder.db.SessionRepository
import larder.db.UnitRepository
import larder.db.UserRepository
import larder.ingredients.IngredientResolver
import larder.ingredients.SidecarIngredientLineParser
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.logging.Logger

private val logger = Logger.getLogger("larder.Main")

// Composition root — every service is constructed here, once, and wired into the router by
// hand. No DI framework; see AGENTS.md.
fun main() {
    val port = (System.getenv("LARDER_PORT") ?: "8080").toInt()
    val dbUrl = System.getenv("LARDER_DB_URL") ?: error("LARDER_DB_URL is required")
    val dbUser = System.getenv("LARDER_DB_USER") ?: error("LARDER_DB_USER is required")
    val dbPassword = System.getenv("LARDER_DB_PASSWORD") ?: error("LARDER_DB_PASSWORD is required")
    val migrationsDir = System.getenv("LARDER_MIGRATIONS_DIR") ?: error("LARDER_MIGRATIONS_DIR is required")
    val ingredientParserUrl = System.getenv("LARDER_INGREDIENT_PARSER_URL")
        ?: error("LARDER_INGREDIENT_PARSER_URL is required")
    val frontendDir = System.getenv("LARDER_FRONTEND_DIR") ?: error("LARDER_FRONTEND_DIR is required")
    // Default comfortably covers the HTTP server's 8-thread executor below, with headroom —
    // same rationale as shelf's identical default.
    val dbPoolSize = (System.getenv("LARDER_DB_POOL_SIZE") ?: "10").toInt()
    val sessionDurationHours = (System.getenv("LARDER_SESSION_DURATION_HOURS") ?: "720").toLong()
    // Real deployments MUST override this to true — plain HTTP local dev needs it off. Same
    // caveat as shelf's identical setting.
    val secureCookies = (System.getenv("LARDER_SECURE_COOKIES") ?: "false").toBoolean()

    val pool = ConnectionPool.open(dbUrl, dbUser, dbPassword, dbPoolSize)
    pool.use { connection -> MigrationRunner(connection, Path.of(migrationsDir)).run() }

    val database = Database(pool)
    val authConfig = AuthConfig(sessionDurationHours, secureCookies)
    val users = UserRepository(database)
    val sessions = SessionRepository(database)
    val recipes = RecipeRepository(database)
    val ingredients = IngredientRepository(database)
    val mealPlan = MealPlanRepository(database)
    val shoppingLists = ShoppingListRepository(database)
    val units = UnitRepository(database)
    val ingredientParser = SidecarIngredientLineParser(ingredientParserUrl)
    val ingredientResolver = IngredientResolver(units, ingredients)

    val versionHandler = VersionHandler(database)
    val registerHandler = RegisterHandler(users, sessions, authConfig)
    val loginHandler = LoginHandler(users, sessions, authConfig)
    val logoutHandler = LogoutHandler(sessions, authConfig.secureCookies)
    val meHandler = MeHandler()
    val recipesListHandler = RecipesListHandler(recipes)
    val recipeGetHandler = RecipeGetHandler(recipes)
    val recipeCreateHandler = RecipeCreateHandler(recipes, ingredientParser, ingredientResolver)
    val recipeUpdateHandler = RecipeUpdateHandler(recipes, ingredientParser, ingredientResolver)
    val recipeDeleteHandler = RecipeDeleteHandler(recipes)
    val recipeImportHandler = RecipeImportHandler(recipes, ingredientParser, ingredientResolver)
    val ingredientMergeHandler = IngredientMergeHandler(ingredients)
    val mealPlanListHandler = MealPlanListHandler(mealPlan)
    val mealPlanCreateHandler = MealPlanCreateHandler(mealPlan, recipes)
    val mealPlanDeleteHandler = MealPlanDeleteHandler(mealPlan)
    val shoppingListCreateHandler = ShoppingListCreateHandler(shoppingLists, recipes, mealPlan)
    val shoppingListsListHandler = ShoppingListsListHandler(shoppingLists)
    val shoppingListGetHandler = ShoppingListGetHandler(shoppingLists)
    val shoppingListDeleteHandler = ShoppingListDeleteHandler(shoppingLists)
    val shoppingListItemHandlers = ShoppingListItemHandlers(shoppingLists)
    val staticFileHandler = StaticFileHandler(Path.of(frontendDir))

    val router = Router()
    router.get("/api/health", ::healthHandler)
    router.get("/api/version", versionHandler::handle)
    router.post("/api/register", registerHandler::handle)
    router.post("/api/login", loginHandler::handle)
    router.post("/api/logout", logoutHandler::handle)
    router.get("/api/me", requireAuth(sessions, users, meHandler::handle))
    router.get("/api/recipes", requireAuth(sessions, users, recipesListHandler::handle))
    router.get("/api/recipes/:id", requireAuth(sessions, users, recipeGetHandler::handle))
    router.post("/api/recipes", requireAuth(sessions, users, recipeCreateHandler::handle))
    router.post("/api/recipes/import", requireAuth(sessions, users, recipeImportHandler::handle))
    router.put("/api/recipes/:id", requireAuth(sessions, users, recipeUpdateHandler::handle))
    router.delete("/api/recipes/:id", requireAuth(sessions, users, recipeDeleteHandler::handle))
    router.post(
        "/api/ingredients/:id/merge-into/:targetId",
        requireAuth(sessions, users, ingredientMergeHandler::handle),
    )
    router.get("/api/meal-plan", requireAuth(sessions, users, mealPlanListHandler::handle))
    router.post("/api/meal-plan", requireAuth(sessions, users, mealPlanCreateHandler::handle))
    router.delete("/api/meal-plan/:id", requireAuth(sessions, users, mealPlanDeleteHandler::handle))
    router.get("/api/shopping-lists", requireAuth(sessions, users, shoppingListsListHandler::handle))
    router.post("/api/shopping-lists", requireAuth(sessions, users, shoppingListCreateHandler::handle))
    router.get("/api/shopping-lists/:id", requireAuth(sessions, users, shoppingListGetHandler::handle))
    router.delete("/api/shopping-lists/:id", requireAuth(sessions, users, shoppingListDeleteHandler::handle))
    router.post("/api/shopping-lists/:id/items", requireAuth(sessions, users, shoppingListItemHandlers::add))
    router.post("/api/shopping-lists/:id/items/merge", requireAuth(sessions, users, shoppingListItemHandlers::merge))
    router.patch("/api/shopping-lists/:id/items/:itemId", requireAuth(sessions, users, shoppingListItemHandlers::update))
    router.delete("/api/shopping-lists/:id/items/:itemId", requireAuth(sessions, users, shoppingListItemHandlers::delete))
    router.serveStatic(staticFileHandler::serve)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(8)
    server.createContext("/", router.toHttpHandler())
    server.start()

    logger.info("larder listening on port $port")
}
