package fr.citizen.worldbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.Queue;

/** Génération de test non destructive : petite place et bâtiments simples. */
public final class CitizenMapPlan {
    private CitizenMapPlan() {}
    public static void create(Queue<BuildQueue.Placement> q,BlockPos o){
        int x=o.getX(), y=o.getY()-1, z=o.getZ();
        disk(q,x,y,z,12,Blocks.POLISHED_ANDESITE.defaultBlockState());
        for(int i=0;i<8;i++){
            double a=Math.PI*2*i/8.0; int bx=x+(int)(Math.cos(a)*22),bz=z+(int)(Math.sin(a)*22);
            building(q,bx,y,bz,i);
        }
        for(int i=0;i<18;i++){
            double a=Math.PI*2*i/18.0; int tx=x+(int)(Math.cos(a)*48),tz=z+(int)(Math.sin(a)*48);
            tree(q,tx,y,tz,18+i%8);
        }
    }
    private static void disk(Queue<BuildQueue.Placement> q,int x,int y,int z,int r,net.minecraft.world.level.block.state.BlockState s){
        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(dx*dx+dz*dz<=r*r)BuildQueue.put(q,new BlockPos(x+dx,y,z+dz),s);
    }
    private static void building(Queue<BuildQueue.Placement> q,int x,int y,int z,int n){
        var wall=(n%2==0?Blocks.SPRUCE_PLANKS:Blocks.DARK_OAK_PLANKS).defaultBlockState();
        for(int dx=-4;dx<=4;dx++)for(int dy=0;dy<=5;dy++)for(int dz=-4;dz<=4;dz++){
            boolean edge=Math.abs(dx)==4||Math.abs(dz)==4;
            if(edge||dy==0||dy==5)BuildQueue.put(q,new BlockPos(x+dx,y+dy,z+dz),wall);
        }
        for(int dx=-3;dx<=3;dx++)for(int dy=1;dy<5;dy++)for(int dz=-3;dz<=3;dz++)BuildQueue.put(q,new BlockPos(x+dx,y+dy,z+dz),Blocks.AIR.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+1,z-4),Blocks.OAK_DOOR.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x,y+1,z),Blocks.CRAFTING_TABLE.defaultBlockState());
        BuildQueue.put(q,new BlockPos(x+2,y+1,z+2),Blocks.BARREL.defaultBlockState());
    }
    private static void tree(Queue<BuildQueue.Placement> q,int x,int y,int z,int h){
        for(int dy=0;dy<h;dy++)BuildQueue.put(q,new BlockPos(x,y+dy,z),Blocks.DARK_OAK_LOG.defaultBlockState());
        for(int dy=h-4;dy<=h+3;dy++){int r=Math.max(2,7-Math.abs(dy-h));disk(q,x,y+dy,z,r,Blocks.DARK_OAK_LEAVES.defaultBlockState());}
    }
}
