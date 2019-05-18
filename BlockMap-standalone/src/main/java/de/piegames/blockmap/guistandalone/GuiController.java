package de.piegames.blockmap.guistandalone;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.controlsfx.control.CheckTreeView;
import org.controlsfx.control.RangeSlider;
import org.controlsfx.control.StatusBar;
import org.controlsfx.dialog.ExceptionDialog;
import org.joml.Vector2ic;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import de.piegames.blockmap.DotMinecraft;
import de.piegames.blockmap.color.BlockColorMap.InternalColorMap;
import de.piegames.blockmap.gui.MapPane;
import de.piegames.blockmap.gui.WorldRendererCanvas;
import de.piegames.blockmap.gui.decoration.DragScrollDecoration;
import de.piegames.blockmap.gui.decoration.GridDecoration;
import de.piegames.blockmap.gui.decoration.Pin;
import de.piegames.blockmap.gui.decoration.Pin.PinType;
import de.piegames.blockmap.gui.decoration.PinDecoration;
import de.piegames.blockmap.guistandalone.RegionFolderProvider.LocalFolderProvider;
import de.piegames.blockmap.guistandalone.RegionFolderProvider.RemoteFolderProvider;
import de.piegames.blockmap.guistandalone.about.AboutDialog;
import de.piegames.blockmap.renderer.RegionRenderer;
import de.piegames.blockmap.renderer.RegionShader;
import de.piegames.blockmap.renderer.RenderSettings;
import de.piegames.blockmap.world.ChunkMetadata;
import de.piegames.blockmap.world.RegionFolder;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

public class GuiController implements Initializable {

	private static Log								log						= LogFactory.getLog(GuiController.class);

	protected RegionRenderer						regionRenderer;
	public WorldRendererCanvas						renderer;
	protected ObjectProperty<RegionFolder>			regionFolder			= new SimpleObjectProperty<>();
	protected ObjectProperty<RegionFolderProvider>	regionFolderProvider	= new SimpleObjectProperty<>();

	protected Path									lastBrowsedPath;
	protected URL									lastBrowsedURL;

	@FXML
	private BorderPane								root;
	@FXML
	private Button									browseButton;
	@FXML
	private StatusBar								statusBar;
	@FXML
	private Label									minHeight, maxHeight;
	@FXML
	private RangeSlider								heightSlider;
	@FXML
	private GridPane								regionSettings;
	@FXML
	private ChoiceBox<String>						shadingBox;
	@FXML
	private ChoiceBox<String>						colorBox;
	@FXML
	private CheckBox								gridBox;
	@FXML
	private CheckBox								pinBox;
	@FXML
	public CheckTreeView<PinType>					pinView;
	public Map<PinType, TreeItem<PinType>>			checkedPins				= new HashMap<>();

	protected MapPane								pane;
	protected ObjectProperty<Path>					currentPath				= new SimpleObjectProperty<>();
	public PinDecoration							pins;

	protected ScheduledExecutorService				backgroundThread		= Executors.newSingleThreadScheduledExecutor(
			new ThreadFactoryBuilder().setNameFormat("pin-background-thread-%d").build());

	public GuiController() {
	}

	@Override
	public void initialize(URL location, ResourceBundle resources) {
		log.debug("Initializing GUI");
		RenderSettings settings = new RenderSettings();
		settings.loadDefaultColors();
		regionRenderer = new RegionRenderer(settings);

		renderer = new WorldRendererCanvas(null);
		root.setCenter(pane = new MapPane(renderer));
		pane.decorationLayers.add(new DragScrollDecoration(renderer.viewport));
		GridDecoration grid = new GridDecoration(renderer.viewport);
		pane.decorationLayers.add(grid);
		grid.visibleProperty().bind(gridBox.selectedProperty());
		pins = new PinDecoration(renderer.viewport);
		pane.pinLayers.add(pins);

		{ /* Status bar initialization */
			statusBar.setSkin(new StatusBarSkin2(statusBar));
			statusBar.progressProperty().bind(renderer.getProgress());
			statusBar.setText(null);
			statusBar.textProperty().bind(renderer.getStatus());

			Label pathLabel = new Label();
			pathLabel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
			pathLabel.textProperty().bind(Bindings.createStringBinding(
					() -> regionFolderProvider.get() == null ? "" : regionFolderProvider.get().getLocation(),
					regionFolderProvider));
			statusBar.getLeftItems().add(pathLabel);

			Label zoomLabel = new Label();
			zoomLabel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
			zoomLabel.textProperty().bind(Bindings.createStringBinding(() -> {
				double scale = renderer.viewport.scaleProperty.get();
				boolean zoomIn = false;
				if (scale < 1) {
					zoomIn = true;
					scale = 1 / scale;
				}
				String text = scale < 3 ? Double.toString((int) (scale * 10) / 10d) : Integer.toString((int) scale);
				if (zoomIn)
					return "1:" + text;
				else
					return text + ":1";
			}, renderer.viewport.scaleProperty));
			statusBar.getRightItems().add(zoomLabel);

			Label mouseLabel = new Label();
			mouseLabel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
			mouseLabel.textProperty().bind(Bindings.createStringBinding(
					() -> "(" + (int) renderer.viewport.mouseWorldProperty.get().x() + ", " + (int) renderer.viewport.mouseWorldProperty.get().y() + ")",
					renderer.viewport.mouseWorldProperty));
			statusBar.getRightItems().add(mouseLabel);
		}

		minHeight.textProperty().bind(Bindings.format("Min: %3.0f", heightSlider.lowValueProperty()));
		maxHeight.textProperty().bind(Bindings.format("Max: %3.0f", heightSlider.highValueProperty()));
		ChangeListener<? super Boolean> heightListener = (e, oldVal, newVal) -> {
			if (oldVal && !newVal) {
				if (e == heightSlider.lowValueChangingProperty())
					settings.minY = (int) Math.round(heightSlider.lowValueProperty().getValue().doubleValue());
				else if (e == heightSlider.highValueChangingProperty())
					settings.maxY = (int) Math.round(heightSlider.highValueProperty().getValue().doubleValue());
				renderer.invalidateTextures();
				renderer.repaint();
			}
		};
		heightSlider.lowValueChangingProperty().addListener(heightListener);
		heightSlider.highValueChangingProperty().addListener(heightListener);

		colorBox.valueProperty().addListener((observer, old, value) -> {
			settings.blockColors = InternalColorMap.values()[colorBox.getSelectionModel().getSelectedIndex()].getColorMap();
			renderer.invalidateTextures();
			renderer.repaint();
		});
		shadingBox.valueProperty().addListener((observer, old, value) -> {
			settings.shader = RegionShader.DEFAULT_SHADERS[shadingBox.getSelectionModel().getSelectedIndex()];
			renderer.invalidateTextures();
			renderer.repaint();
		});

		{ /* Pin checkbox icon */
			ImageView image = new ImageView(PinType.ANY_PIN.image);
			image.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> pinBox.getFont().getSize() * 1.5, pinBox.fontProperty()));
			image.setSmooth(true);
			image.setPreserveRatio(true);
			pinBox.setGraphic(image);
		}
		{ /* Pin tree */
			initPinCheckboxes(PinType.ANY_PIN, null, pinView);
			pinView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
			/* Map the set of selected tree items to pins.visiblePins */
			pins.visiblePins.bind(Bindings.createObjectBinding(() -> pinBox.isSelected() ? pinView.getCheckModel().getCheckedItems().stream().map(t -> t
					.getValue()).collect(Collectors.toCollection(FXCollections::observableSet)) : FXCollections.emptyObservableSet(), pinView.getCheckModel()
							.getCheckedItems(), pinBox.selectedProperty()));
			/*
			 * Disable the pin view if either pins are disabled or settings are disabled (indicated through pinBox.disabledProperty, which is set in the
			 * following code block).
			 */
			pinView.disableProperty().bind(Bindings.createBooleanBinding(() -> pinBox.isDisabled() || !pinBox.isSelected(), pinBox.selectedProperty(), pinBox
					.disabledProperty()));
		}

		{
			ChangeListener<? super RegionFolderProvider> regionFolderProviderListener = (observable, previous, val) -> {
				regionSettings.getChildren().clear();
				if (val == null) {
					regionFolder.unbind();
					regionFolder.set(null);
				} else {
					regionFolder.bind(val.folderProperty());
					regionSettings.getChildren().addAll(val.getGUI());
				}
				regionSettings.setVisible(!regionSettings.getChildren().isEmpty());
				boolean disabled = val == null ? true : val.hideSettings();
				heightSlider.setDisable(disabled);
				colorBox.setDisable(disabled);
				shadingBox.setDisable(disabled);
				pinBox.setDisable(val == null);
				gridBox.setDisable(val == null);

				renderer.repaint();
			};
			regionFolderProvider.addListener(regionFolderProviderListener);
			/* Force listener update */
			regionFolderProviderListener.changed(regionFolderProvider, null, null);
		}

		renderer.regionFolder.bind(regionFolder);
		renderer.regionFolder.addListener((observable, previous, val) -> {
			if (val != null)
				this.pins.loadWorld(val.listRegions(), val.getPins().map(pins -> Pin.convertStatic(pins, backgroundThread, renderer.viewport)).orElse(
						Collections.emptySet()));
			else
				this.pins.loadWorld(Collections.emptyList(), Collections.emptyList());
		});
		renderer.getChunkMetadata().addListener((MapChangeListener<Vector2ic, Map<Vector2ic, ChunkMetadata>>) change -> {
			/*
			 * This works because the only operations are clear() and additions. There are no put operations that overwrite a previously existing item.
			 */
			if (change.getValueRemoved() != null)
				GuiController.this.pins.reloadWorld();
			if (change.getValueAdded() != null)
				GuiController.this.pins.loadRegion(change.getKey(), Pin.convertDynamic(change.getValueAdded(), renderer.viewport));
		});
	}

	/**
	 * Recursive pre-order traversal of the pin type hierarchy tree. Generated items are added automatically.
	 *
	 * @param type
	 *            the current type to add
	 * @param parent
	 *            the parent tree item to add this one to. <code>null</code> if {@code type} is the root type, in this case the generated tree
	 *            item will be used as root for the tree directly.
	 * @param tree
	 *            the tree containing the items
	 */
	private void initPinCheckboxes(PinType type, CheckBoxTreeItem<PinType> parent, CheckTreeView<PinType> tree) {
		ImageView image = new ImageView(type.image);
		/*
		 * The only way so set the size of an image relative to the text of the label is to bind its height to a font size. Since tree items don't
		 * possess a fontProperty (it's hidden behind a cell factory implementation), we have to use the next best labeled node (pinBox in this
		 * case). This will only work if we don't change any font sizes.
		 */
		image.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> pinBox.getFont().getSize() * 1.5, pinBox.fontProperty()));
		image.setSmooth(true);
		image.setPreserveRatio(true);
		CheckBoxTreeItem<PinType> ret = new CheckBoxTreeItem<>(type, image);

		if (parent == null)
			tree.setRoot(ret);
		else
			parent.getChildren().add(ret);

		for (PinType sub : type.getChildren())
			initPinCheckboxes(sub, ret, tree);

		ret.setExpanded(type.expandedByDefault);
		if (type.selectedByDefault) {
			pins.visiblePins.add(type);
			tree.getCheckModel().check(ret);
		}
		checkedPins.put(type, ret);
	}

	@FXML
	public void browseFolder() {
		DirectoryChooser dialog = new DirectoryChooser();
		File f = (lastBrowsedPath == null) ? DotMinecraft.DOTMINECRAFT.resolve("saves").toFile() : lastBrowsedPath.getParent().toFile();
		if (!f.isDirectory())
			f = DotMinecraft.DOTMINECRAFT.resolve("saves").toFile();
		if (!f.isDirectory())
			f = null;
		dialog.setInitialDirectory(f);
		f = dialog.showDialog(null);
		if (f != null) {
			lastBrowsedPath = f.toPath();
			regionFolderProvider.set(RegionFolderProvider.byPath(lastBrowsedPath, regionRenderer));
		}
	}

	@FXML
	public void browseFile() {
		FileChooser dialog = new FileChooser();
		File f = (lastBrowsedPath == null) ? DotMinecraft.DOTMINECRAFT.resolve("saves").toFile() : lastBrowsedPath.getParent().toFile();
		if (!f.isDirectory())
			f = DotMinecraft.DOTMINECRAFT.resolve("saves").toFile();
		if (!f.isDirectory())
			f = null;
		dialog.setInitialDirectory(f);
		f = dialog.showOpenDialog(null);
		if (f != null) {
			lastBrowsedPath = f.toPath();
			regionFolderProvider.set(new LocalFolderProvider(lastBrowsedPath));
		}
	}

	@FXML
	public void loadRemote() {
		TextInputDialog dialog = new TextInputDialog();
		dialog.setTitle("Load remote world");
		dialog.setHeaderText("Enter the URL to the remote world you want to load");
		dialog.setGraphic(null);
		dialog.showAndWait().ifPresent(s -> {
			try {
				regionFolderProvider.set(new RemoteFolderProvider(new URI(s)));
			} catch (URISyntaxException | IllegalArgumentException e) {
				log.warn("Malformed input uri", e);
			}
		});
	}

	@FXML
	public void reloadWorld() {
		if (regionFolderProvider.get() != null)
			regionFolderProvider.get().reload();
	}

	@FXML
	public void unload() {
		regionFolderProvider.set(null);
	}

	public void load(RegionFolderProvider world) {
		regionFolderProvider.set(world);
	}

	@FXML
	public void exit() {
		Platform.exit();
	}

	@FXML
	public void showAbout() {
		try {
			new AboutDialog().showAndWait();
		} catch (Exception e) {
			log.error("Could not show 'about' dialog, please file a bug report", e);
			ExceptionDialog d = new ExceptionDialog(e);
			d.setTitle("Could not load dialog");
			d.setHeaderText("Please file a bug report");
			d.showAndWait();
		}
	}

	public void shutDown() {
		renderer.shutDown();
		backgroundThread.shutdownNow();
		try {
			backgroundThread.awaitTermination(1, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			log.warn("Background thread did not finish", e);
		}
	}
}