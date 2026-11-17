package fr.citizen.worldbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Queue;

/**
 * Citizen fantasy capital layout. All structures are generated as real vanilla blocks.
 * Coordinates are relative to the player's selected origin.
 */
public final class CitizenMapPlan {
    private CitizenMapPlan() {}

    public static void create(Queue<BuildQueue.Placement> q, BlockPos o) {
        int x=o.getX(), y=o.getY()-1, z=o.getZ();

        // Main green and radial road network
        disk(q,x,y,z,20,Blocks.STONE_BRICKS.defaultBlockState());
        disk(q,x,y+1,z,14,Blocks.POLISHED_ANDESITE.defaultBlockState());
        road(q,x,y+1,z,0,0,115);
        road(q,x,y+1,z,1,0,115);
        road(q,x,y+1,z,0,1,115);
        road(q,x,y+1,z,0,-1,115);

        // Central castle, keep, towers and walls
        castle(q,x,y+1,z);
        // Eight commercial buildings around the central square
        for(int i=0;i<8;i++){
            double a=Math.PI*2*i/8.0;
            int bx=x+(int)(Math.cos(a)*34), bz=z+(int)(Math.sin(a)*34);
            shop(q,bx,y+1,bz,i);
        }

        // Five residential districts: ten varied homes per district
        for(int i=0;i<50;i++){
            int district=i/10, slot=i%10;
            double angle=(district*Math.PI*2/5.0)+(slot%5)*0.105;
            int radius=62+(slot/5)*17;
            int hx=x+(int)(Math.cos(angle)*radius);
            int hz=z+(int)(Math.sin(angle)*radius);
            house(q,hx,y+1,hz,i);
        }

        // Civic buildings
        civic(q,x-48,y+1,z-38,0); // hospital
        civic(q,x+48,y+1,z-38,1); // magic academy
        factionKeep(q,x-112,y+1,z+15,false); // Dragon Slayers
        factionKeep(q,x+112,y+1,z+15,true);  // Dragon Riders

        // Giant forest belt around the settlement
        for(int i=0;i<180;i++){
            double a=i*Math.PI*2/180.0;
            int radius=145+(i%4)*9;
            int tx=x+(int)(Math.cos(a)*radius);
            int tz=z+(int)(Math.sin(a)*radius);
            giantTree(q,tx,y+1,tz,22+(i%13));
        }
    }

    private static void road(Queue<BuildQueue.Placement> q,int x,int y,int z,int dx,int dz,int length){
        for(int t=-length/2;t<=length/2;t++) for(int w=-3;w<=3;w++){
            int px=x+(dx==0?w:t), pz=z+(dz==0?w:t);
            BuildQueue.put(q,new BlockPos(px,y,pz), (w==0?Blocks.POLISHED_ANDESITE:Blocks.STONE_BRICKS).defaultBlockState());
        }
    }
    private static void disk(Queue<BuildQueue.Placement> q,int x,int y,int z,int r,BlockState s){
        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(dx*dx+dz*dz<=r*r)BuildQueue.put(q,new BlockPos(x+dx,y,z+dz),s);
    }
    private static void shell(Queue<BuildQueue.Placement> q,int x,int y,int z,int rx,int rz,int h,BlockState wall,BlockState roof){
        for(int dx=-rx;dx<=rx;dx++)for(int dz=-rz;dz<=rz;dz++){
            BuildQueue.put(q,new BlockPos(x+dx,y,z+dz),Blocks.STONE_BRICKS.defaultBlockState());
            for(int dy=1;dy<=h;dy++) if(Math.abs(dx)==rx||Math.abs(dz)==rz)
                BuildQueue.put(q,new BlockPos(x+dx,y+dy,z+dz),wall);
            if(Math.abs(dx)==rx||Math.abs(dz)==rz)
                BuildQueue.put(q,new BlockPos(x+dx,y+h+1,z+dz),roof);
        }
        for(int dx=-rx+1;dx<rx;dx++)for(int dz=-rz+1;dz<rz;dz++)
            for(int dy=1;dy<=h;dy++)BuildQueue.put(q,new BlockPos(x+dx,y+dy,z+dz),Blocks.AIR.defaultBlockState());
        // Doorway
        BuildQueue.put(q,new BlockPos(x,y+1,z-rz),Blocks.AIR.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+2,z-rz),Blocks.AIR.defaultBlockState());
    }
    private static void house(Queue<BuildQueue.Placement> q,int x,int y,int z,int n){
        BlockState[] walls={Blocks.SPRUCE_PLANKS.defaultBlockState(),Blocks.DARK_OAK_PLANKS.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),Blocks.MANGROVE_PLANKS.defaultBlockState(),Blocks.BIRCH_PLANKS.defaultBlockState()};
        BlockState[] roofs={Blocks.DARK_OAK_STAIRS.defaultBlockState(),Blocks.SPRUCE_STAIRS.defaultBlockState(),Blocks.BRICK_STAIRS.defaultBlockState(),Blocks.MANGROVE_STAIRS.defaultBlockState(),Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()};
        int rx=4+(n%3), rz=4+((n/3)%2), h=4+(n%3);
        shell(q,x,y,z,rx,rz,h,walls[n%walls.length],roofs[n%roofs.length]);
        for(int dx=-rx+1;dx<rx;dx++)for(int dz=-rz+1;dz<rz;dz++)
            BuildQueue.put(q,new BlockPos(x+dx,y+h+2-Math.max(Math.abs(dx),Math.abs(dz))/2,z+dz),roofs[n%roofs.length]);
        BuildQueue.put(q,new BlockPos(x,y+1,z-rz),Blocks.OAK_DOOR.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x+1,y+1,z+1),Blocks.CRAFTING_TABLE.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x-1,y+1,z+1),Blocks.BARREL.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x+rx,y+2,z),Blocks.LANTERN.defaultBlockState());
    }
    private static void shop(Queue<BuildQueue.Placement> q,int x,int y,int z,int n){
        BlockState wall=(n%2==0?Blocks.SPRUCE_PLANKS:Blocks.DARK_OAK_PLANKS).defaultBlockState();
        shell(q,x,y,z,6,5,5,wall,Blocks.STONE_BRICK_STAIRS.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+1,z-5),Blocks.OAK_DOOR.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x-2,y+2,z-5),Blocks.BARREL.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x+2,y+2,z-5),Blocks.BARREL.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+1,z),new BlockState[]{Blocks.ANVIL.defaultBlockState(),Blocks.BLAST_FURNACE.defaultBlockState(),Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),Blocks.FLETCHING_TABLE.defaultBlockState(),Blocks.SMITHING_TABLE.defaultBlockState(),Blocks.BREWING_STAND.defaultBlockState(),Blocks.LECTERN.defaultBlockState(),Blocks.CAULDRON.defaultBlockState()}[n]);
    }
    private static void civic(Queue<BuildQueue.Placement> q,int x,int y,int z,int type){
        shell(q,x,y,z,12,10,9,type==0?Blocks.QUARTZ_BRICKS.defaultBlockState():Blocks.PURPUR_BLOCK.defaultBlockState(),Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState());
        for(int dx=-8;dx<=8;dx+=4)for(int dz=-6;dz<=6;dz+=4)
            BuildQueue.put(q,new BlockPos(x+dx,y+1,z+dz),type==0?Blocks.WHITE_BED.defaultBlockState():Blocks.ENCHANTING_TABLE.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+10,z),Blocks.AMETHYST_BLOCK.defaultBlockState());
    }
    private static void castle(Queue<BuildQueue.Placement> q,int x,int y,int z){
        shell(q,x,y,z,22,18,15,Blocks.STONE_BRICKS.defaultBlockState(),Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState());
        for(int dx=-25;dx<=25;dx++)for(int dz=-21;dz<=21;dz++)
            if(Math.abs(dx)==25||Math.abs(dz)==21)for(int dy=1;dy<=9;dy++)
                BuildQueue.put(q,new BlockPos(x+dx,y+dy,z+dz),Blocks.STONE_BRICKS.defaultBlockState());
        for(int sx:new int[]{-25,25})for(int sz:new int[]{-21,21})
            tower(q,x+sx,y,z+sz,6,19);
        tower(q,x,y,z,8,27);
    }
    private static void tower(Queue<BuildQueue.Placement> q,int x,int y,int z,int r,int h){
        for(int dy=0;dy<h;dy++)disk(q,x,y+dy,z,r,Blocks.STONE_BRICKS.defaultBlockState());
        for(int dy=1;dy<h-1;dy++)disk(q,x,y+dy,z,r-1,Blocks.AIR.defaultBlockState());
        disk(q,x,y+h,z,r+2,Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState());
    }
    private static void factionKeep(Queue<BuildQueue.Placement> q,int x,int y,int z,boolean riders){
        BlockState material=riders?Blocks.COPPER_BLOCK.defaultBlockState():Blocks.BLACKSTONE.defaultBlockState();
        shell(q,x,y,z,15,13,10,material,Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState());
        for(int i=-1;i<=1;i++)tower(q,x+i*12,y,z+10,3,15);
        BuildQueue.put(q,new BlockPos(x,y+11,z),riders?Blocks.LIGHTNING_ROD.defaultBlockState():Blocks.SOUL_LANTERN.defaultBlockState());
    }
    private static void giantTree(Queue<BuildQueue.Placement> q,int x,int y,int z,int h){
        for(int dy=0;dy<h;dy++)BuildQueue.put(q,new BlockPos(x,y+dy,z),Blocks.DARK_OAK_LOG.defaultBlockState());
        for(int dy=h-6;dy<=h+5;dy++){
            int r=Math.max(3,10-Math.abs(dy-h));
            disk(q,x,y+dy,z,r,Blocks.DARK_OAK_LEAVES.defaultBlockState());
        }
        for(int k=0;k<4;k++){
            int bx=x+(k%2==0?1:-1)*5,bz=z+(k<2?1:-1)*5;
            for(int dy=h/2;dy<h/2+7;dy++)BuildQueue.put(q,new BlockPos(bx, y+dy,bz),Blocks.DARK_OAK_LOG.defaultBlockState());
        }
    }
}
