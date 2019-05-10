package de.piegames.blockmap.renderer;

import java.util.Map;

import de.piegames.blockmap.MinecraftVersion;
import de.piegames.blockmap.color.BiomeColorMap;
import de.piegames.blockmap.color.BlockColorMap;
import de.piegames.blockmap.color.BlockColorMap.InternalColorMap;
import de.piegames.blockmap.renderer.RegionShader.ReliefShader;

public class RenderSettings {

	public int									minX	= Integer.MIN_VALUE;
	public int									maxX	= Integer.MAX_VALUE;
	public int									minY	= Integer.MIN_VALUE;
	public int									maxY	= Integer.MAX_VALUE;
	public int									minZ	= Integer.MIN_VALUE;
	public int									maxZ	= Integer.MAX_VALUE;

	public Map<MinecraftVersion, BlockColorMap>	blockColors;
	public BiomeColorMap						biomeColors;
	public RegionShader							shader	= new ReliefShader();

	public RenderSettings() {
	}

	public void loadDefaultColors() {
		blockColors = InternalColorMap.DEFAULT.getColorMap();
		biomeColors = BiomeColorMap.loadDefault();
	}
}