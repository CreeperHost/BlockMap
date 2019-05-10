package de.piegames.blockmap.world;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import com.flowpowered.nbt.regionfile.RegionFile;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;

import de.piegames.blockmap.MinecraftDimension;
import de.piegames.blockmap.renderer.RegionRenderer;
import de.piegames.blockmap.world.ChunkMetadata.ChunkRenderState;
import de.piegames.blockmap.world.Region.BufferedRegion;
import de.piegames.blockmap.world.Region.LocalSavedRegion;
import de.piegames.blockmap.world.Region.SavedRegion;
import io.gsonfire.GsonFireBuilder;

/**
 * This class represents a mapping from region file positions in a world to {@link BufferedImage}s of that rendered region. How this is done
 * is up to the implementation.
 */
public abstract class RegionFolder {

	public static final Gson GSON = new GsonFireBuilder()
			.enableExposeMethodResult()
			.registerTypeSelector(Vector2ic.class, e -> Vector2i.class)
			.registerTypeSelector(Vector3ic.class, e -> Vector3i.class)
			.registerTypeSelector(ChunkMetadata.class, e -> ChunkRenderState.valueOf(e.getAsJsonObject().getAsJsonPrimitive("renderState").getAsString()).clazz)
			.createGsonBuilder()
			.setPrettyPrinting()
			.create();

	/**
	 * Lists all existing region file in this RegionFolder. If one of the returned positions is passed to {@link #render(Vector2ic)}, it must
	 * not return {@code null}.
	 */
	public abstract Set<Vector2ic> listRegions();

	/**
	 * Generates an image of the region file at the given position, however this might be done. Will return {@code null} if the passed position
	 * is not contained in {@link #listRegions()}. This method will block until the image data is retrieved and may throw an exception if it
	 * fails to do so.
	 * 
	 * @param pos
	 *            the position of the region file to render
	 * @return the rendered region file as {@link BufferedImage} or {@code null} if {@code listRegions().contains(pos)} evaluates to
	 *         {@code false}
	 * @throws IOException
	 *             if the image could not be retrieved
	 */
	public abstract Region render(Vector2ic pos) throws IOException;

	/** Returns the pins of this specific world or {@code Optional.empty()} if they are not loaded. */
	public abstract Optional<WorldPins> getPins();

	/**
	 * This {@link RegionFolder} implementation will render region files using a {@link RegionRenderer}. Calling {@link #render(Vector2ic)}
	 * repeatedly on the same location will render the same image multiple times.
	 */
	public static class WorldRegionFolder extends RegionFolder {

		static final Pattern					rfpat	= Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

		protected final Map<Vector2ic, Path>	regions;
		protected final RegionRenderer			renderer;
		protected WorldPins						pins;

		/**
		 * @param file
		 *            map region coordinates to paths, which point to the respective file. Those are treated as the "world" represented by this
		 *            RegionFolder. May not be {@code null}.
		 * @param pins
		 *            The pins of this world. May not be {@code null}.
		 * @param renderer
		 *            the {@link RegionRenderer} used to render the files. May not be {@code null}.
		 * @see #load(Path, RegionRenderer)
		 * @see #load(Path, MinecraftDimension, RegionRenderer)
		 * @throws NullPointerException
		 *             if any of the arguments is {@code null}
		 */
		public WorldRegionFolder(Map<Vector2ic, Path> files, RegionRenderer renderer) {
			this.regions = Objects.requireNonNull(files);
			this.renderer = Objects.requireNonNull(renderer);
		}

		@Override
		public Set<Vector2ic> listRegions() {
			return Collections.unmodifiableSet(regions.keySet());
		}

		@Override
		public BufferedRegion render(Vector2ic pos) throws IOException {
			if (regions.containsKey(pos))
				return renderer.render(pos, new RegionFile(regions.get(pos)));
			else
				return null;
		}

		public Path getPath(Vector2ic pos) {
			return regions.get(pos);
		}

		@Override
		public Optional<WorldPins> getPins() {
			return Optional.ofNullable(pins);
		}

		public void setPins(WorldPins pins) {
			this.pins = pins;
		}

		/**
		 * Loads a region folder from a given world path.
		 * 
		 * @param world
		 *            the path to the world folder. It has to be a directory pointing to a valid Minecraft world. World folders usually contain a
		 *            {@code level.dat} file.
		 * @param dimension
		 *            the Minecraft dimension to render. It will be used to resolve the region folder path from the world path.
		 * @param loadPins
		 *            if the pins should be loaded too
		 * @see #load(Path, MinecraftDimension, RegionRenderer)
		 */
		public static WorldRegionFolder load(Path world, MinecraftDimension dimension, RegionRenderer renderer, boolean loadPins) throws IOException {
			WorldRegionFolder folder = load(world.resolve(dimension.getRegionPath()), renderer);
			if (loadPins)
				folder.setPins(WorldPins.loadFromWorld(world, dimension));
			return folder;
		}

		/**
		 * Loads a region folder from a given path. All region files found in this folder (not searching recursively) will be added to the returned
		 * object. Files added later on won't be recognized. Removing files will lead to errors when trying to render them. All files whose name
		 * matches {@code ^r\.(-?\d+)\.(-?\d+)\.mca$} are taken. If one of them isn't a proper region file, rendering it will fail.
		 * 
		 * @param regionFolder
		 *            the path to the folder containing all region files. This folder is commonly called {@code region} and is situated inside a
		 *            Minecraft world, but this is not a hard requirement. It has to be a directory.
		 */
		public static WorldRegionFolder load(Path regionFolder, RegionRenderer renderer) throws IOException {
			Map<Vector2ic, Path> files = new HashMap<>();
			for (Path p : Files.list(regionFolder).collect(Collectors.toList())) {
				Matcher m = rfpat.matcher(p.getFileName().toString());
				if (m.matches())
					files.put(new Vector2i(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), p);
			}
			return new WorldRegionFolder(files, renderer);
		}
	}

	/**
	 * A RegionFolder implementation that loads already rendered images from the disk. To find them, a save file is passed in the constructor.
	 * It is abstract to work on local systems as well as on remote servers.
	 * 
	 * @see LocalRegionFolder LocalRegionFolder for loading files on your hard drive
	 * @see RemoteRegionFolder RemoteRegionFolder for loading files via uri, either local or on servers
	 * @param T
	 *            the type of the file mapping, like URL, URI, Path, File, etc.
	 */
	public static abstract class SavedRegionFolder<T, R extends SavedRegion> extends RegionFolder {

		protected final Map<Vector2ic, R>	regions;
		protected final Optional<WorldPins>	pins;

		/**
		 * Creates the region folder with a custom mapping
		 * 
		 * @param regions
		 *            a mapping from each region file's position in the world to a T that represents its location for loading
		 * @param pins
		 *            the pins of this world. May be {@code null} if they haven't been loaded.
		 * @see #parseSaved(JsonElement)
		 */
		protected SavedRegionFolder(Map<Vector2ic, R> regions, Optional<WorldPins> pins) {
			this.regions = Collections.unmodifiableMap(regions);
			this.pins = pins;
		}

		/**
		 * Loads a json file that contains the information about all rendered files.
		 * 
		 * @see #parseSaved(JsonElement)
		 */
		@SuppressWarnings("unchecked")
		protected SavedRegionFolder(T file, String name) throws IOException {
			Map<String, JsonObject> saved = parseSaved(new JsonParser().parse(new InputStreamReader(getInputStream(file))));
			/* Parse the saved file and stream it to a map */
			JsonObject rawFile = null;
			if (name != null) {
				rawFile = saved.get(name);
				Objects.requireNonNull(rawFile, "The specified map was not found in this file");
			} else {
				if (saved.size() != 1)
					throw new IllegalArgumentException("The specified file contains more than one saved map, but no name was given");
				rawFile = saved.values().iterator().next();
			}
			pins = Optional.ofNullable(GSON.fromJson(rawFile.get("pins"), WorldPins.class));
			regions = ((List<RegionHelper>) GSON.fromJson(rawFile.getAsJsonArray("regions"), new TypeToken<List<RegionHelper>>() {
			}.getType())).stream().collect(Collectors.toMap(r -> new Vector2i(r.x, r.z), r -> getRegion(r, file)));
		}

		@Override
		public R render(Vector2ic pos) throws IOException {
			return regions.get(pos);
		}

		/** Mapping from the path type T to an input stream. */
		protected abstract InputStream getInputStream(T path) throws IOException;

		protected abstract R getRegion(RegionHelper rawRegion, T basePath);

		@Override
		public Set<Vector2ic> listRegions() {
			return Collections.unmodifiableSet(regions.keySet());
		}

		@Override
		public Optional<WorldPins> getPins() {
			return pins;
		}

		/**
		 * Parses a save file which describes rendered worlds. It is a json file containing json objects, with each one representing one "world":
		 * 
		 * <pre>
		 * {
		 *     "name": &lt;STRING>,
		 *     "regions": [&lt;REGION ARRAY>]
		 * }
		 * </pre>
		 * 
		 * Each region is represented like this:
		 * 
		 * <pre>
		 * {"x": &lt;NUMBER>, "z": &lt;NUMBER>, "image": &lt;STRING>, "metadata": [&lt;ARRAY OF 32*32 ELEMENTS>]}
		 * </pre>
		 * 
		 * If the top-level element is an object, it will be a world. If it is an array, it will represent a list of worlds. More tags are going to
		 * be added in the future.
		 * 
		 * @return A mapping from world names to a {@link JsonObject} containing their data. Each JsonObject may be converted directly to a
		 *         {@link RegionHelper} using GSON.
		 */
		public static Map<String, JsonObject> parseSaved(JsonElement parsed) {
			Map<String, JsonObject> saved = new HashMap<>();
			if (parsed.isJsonArray()) {
				for (JsonElement e : parsed.getAsJsonArray()) {
					JsonObject o = e.getAsJsonObject();
					saved.put(o.getAsJsonPrimitive("name").getAsString(), o);
				}
			} else {
				JsonObject o = parsed.getAsJsonObject();
				saved.put(o.getAsJsonPrimitive("name").getAsString(), o);
			}
			return saved;
		}
	}

	/**
	 * An implementation of {@link SavedRegionFolder} based on the Java {@link Path} API. Use it for accessing files from your local file
	 * system, but Java paths work with other URI schemata as well. Check {@link FileSystemProvider#installedProviders()} for more information.
	 * (There is even an URLSystemProvider somewhere on GitHub ...)
	 */
	public static class LocalRegionFolder extends SavedRegionFolder<Path, LocalSavedRegion> {
		protected LocalRegionFolder(Map<Vector2ic, LocalSavedRegion> regions, Optional<WorldPins> pins) {
			super(regions, pins);
		}

		public LocalRegionFolder(Path file, String name) throws IOException {
			super(file, name);
		}

		@Override
		protected InputStream getInputStream(Path path) throws IOException {
			return Files.newInputStream(path);
		}

		@Override
		protected LocalSavedRegion getRegion(RegionHelper rawRegion, Path basePath) {
			return new LocalSavedRegion(
					new Vector2i(rawRegion.x, rawRegion.z),
					basePath.resolveSibling(rawRegion.image),
					rawRegion.metadata.stream().collect(Collectors.toMap(meta -> meta.position, Function.identity())));
		}
	}

	/**
	 * An implementation of {@link SavedRegionFolder} based on URIs. It is intended for primary use on remote servers, but with the {@code file}
	 * schema it can open local files as well.
	 */
	public static class RemoteRegionFolder extends SavedRegionFolder<URI, SavedRegion> {

		protected RemoteRegionFolder(Map<Vector2ic, SavedRegion> regions, Optional<WorldPins> pins) {
			super(regions, pins);
		}

		public RemoteRegionFolder(URI file, String name) throws IOException {
			super(file, name);
		}

		@Override
		protected InputStream getInputStream(URI path) throws IOException {
			return path.toURL().openStream();
		}

		@Override
		protected SavedRegion getRegion(RegionHelper rawRegion, URI basePath) {
			return new SavedRegion(
					new Vector2i(rawRegion.x, rawRegion.z),
					basePath.resolve(rawRegion.image),
					Optional.ofNullable(rawRegion.metadata).map(List::stream).orElse(Stream.empty())
							.collect(Collectors.toMap(meta -> meta.position, Function.identity())));
		}
	}

	/**
	 * This {@link RegionFolder} wraps a {@link WorldRegionFolder} in a way that each rendered image will be written to disk to avoid
	 * re-rendering. It can be used to create save files to load in {@link SavedRegionFolder}s.
	 */
	public static class CachedRegionFolder extends RegionFolder {

		protected WorldRegionFolder					world;
		protected boolean							lazy;
		protected Path								imageFolder;
		protected Map<Vector2ic, LocalSavedRegion>	cache	= new HashMap<>();

		/**
		 * @param world
		 *            the renderer used to create images of region files if they haven't been rendered yet
		 * @param lazy
		 *            if set to false, no cached files will be returned for re-rendering. If set to true, a re-render will load the image from disk
		 *            if the respective region file has not been modified since then (based on the timestamp). Laziness has the effect that changing
		 *            the render settings will not cause already rendered files to be updated.
		 * @param imageFolder
		 *            the folder where all the rendered images will be stored. The images will be named like their region file name, but with the
		 *            {@code .mca} replaced with {@code .png}.
		 */
		public CachedRegionFolder(WorldRegionFolder world, boolean lazy, Path imageFolder) {
			this.lazy = lazy;
			this.world = Objects.requireNonNull(world);
			this.imageFolder = Objects.requireNonNull(imageFolder);
		}

		/**
		 * If the image folder already contains a matching image for this position <b>and</b> the {@code lazy} flag was set in the constructor
		 * <b>and</b> the saved file is newer than the region file, this image will be returned. Otherwise, it will be rendered again and written to
		 * disk.
		 * 
		 * @see SavedRegionFolder#render(Vector2ic)
		 */
		@Override
		public Region render(Vector2ic pos) throws IOException {
			Path region = world.getPath(pos);
			if (region == null)
				return null;
			Path image = imageFolder.resolve(region.getFileName().toString().replace(".mca", ".png"));
			if (cache.containsKey(pos)
					&& lazy && Files.getLastModifiedTime(image).compareTo(Files.getLastModifiedTime(region)) > 0) {
				return cache.get(pos);
			} else {
				BufferedRegion rendered = world.render(pos);
				cache.put(pos, rendered.save(image));
				return rendered;
			}
		}

		@Override
		public Set<Vector2ic> listRegions() {
			return world.listRegions();
		}

		@Override
		public Optional<WorldPins> getPins() {
			return world.getPins();
		}

		/** A link to the {@link WorldRegionFolder} that is cached by this object. */
		public WorldRegionFolder getWorldRegionFolder() {
			return world;
		}

		/**
		 * Transforms this object into a {@link LocalRegionFolder} for further use. The returned object will know of every region file in
		 * {@link #listRegions()}, even if it has not been rendered yet. In that case, rendering those from the returned object will throw an error.
		 */
		public LocalRegionFolder save() {
			return new LocalRegionFolder(Collections.unmodifiableMap(cache), world.getPins());
		}

		/** @see #save(Path, String, boolean) */
		public void save(Path file, String name) throws IOException {
			save(file, name, true);
		}

		/**
		 * Saves the paths to all rendered files (and to files that have yet to be rendered; see {@link #save()}) into a save file which will be
		 * accepted by {@link SavedRegionFolder#parseSaved(JsonElement)}.
		 * 
		 * @param file
		 *            where to write this information to. If the file already exist, the data will be appended, keeping the existing one intact.
		 * @param name
		 *            the name of the saved world in that file
		 * @param relativePaths
		 *            wether to use relative paths for referencing the saved images
		 */
		public void save(Path file, String name, boolean relativePaths) throws IOException {
			Collection<JsonObject> existing = Files.exists(file) ? SavedRegionFolder.parseSaved(new JsonParser().parse(new String(Files.readAllBytes(file))))
					.values() : Collections.emptyList();
			try (JsonWriter writer = RegionFolder.GSON.newJsonWriter(Files.newBufferedWriter(file, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING))) {
				if (!existing.isEmpty()) {
					writer.beginArray();
					for (JsonObject e : existing)
						if (!name.equals(e.getAsJsonPrimitive("name").getAsString()))
							writer.jsonValue(RegionFolder.GSON.toJson(e));
				}
				writer.beginObject();
				writer.name("name");
				writer.value(name);
				writer.name("regions");
				writer.beginArray();
				for (Entry<Vector2ic, LocalSavedRegion> e : cache.entrySet()) {
					writer.beginObject();

					writer.name("x");
					writer.value(e.getKey().x());
					writer.name("z");
					writer.value(e.getKey().y());
					writer.name("image");
					Path value = imageFolder.resolve(e.getValue().getPath().getFileName().toString().replace(".mca", ".png"));
					if (relativePaths)
						value = file.normalize().getParent().relativize(value.normalize());
					writer.value(value.toString());

					writer.name("metadata");
					writer.jsonValue(RegionFolder.GSON.toJson(e.getValue().getChunkMetadata().values()));

					writer.endObject();
				}
				writer.endArray();
				writer.endObject();
				if (!existing.isEmpty())
					writer.endArray();
				writer.flush();
			}
		}
	}

	static class RegionHelper {
		int					x, z;
		String				image;
		List<ChunkMetadata>	metadata;
	}
}