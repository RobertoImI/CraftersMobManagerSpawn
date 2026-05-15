package org.crafterscr.craftersmobmanagerspawn.data;

import net.minecraft.core.BlockPos;

public class SpawnPointData {

    private int x;
    private int y;
    private int z;

    public SpawnPointData() {
    }

    public SpawnPointData(BlockPos pos) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    public String asText() {
        return x + " " + y + " " + z;
    }
}