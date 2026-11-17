package fr.citizen.worldbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.Queue;

public final class BuildQueue {
    record Placement(BlockPos pos, BlockState state) {}
    private static final Queue<Placement> QUEUE=new ArrayDeque<>();
    private static Level level;
    private static long placed;
    private static long total;
    private static boolean running;
    private BuildQueue() {}
    public static boolean isRunning(){return running;}
    public static String status(){return running ? "En cours : "+placed+" / "+total : "Inactif. Dernier total : "+placed;}
    public static void start(Level target, BlockPos origin){
        QUEUE.clear(); level=target; placed=0; total=0;
        CitizenMapPlan.create(QUEUE,origin); total=QUEUE.size(); running=true;
    }
    public static void cancel(){QUEUE.clear();running=false;}
    public static void tick(){
        if(!running || level==null)return;
        for(int i=0;i<1200 && !QUEUE.isEmpty();i++){
            Placement p=QUEUE.poll(); level.setBlock(p.pos(),p.state(),Block.UPDATE_ALL); placed++;
        }
        if(QUEUE.isEmpty()){running=false;CitizenWorldBuilder.LOGGER.info("Citizen prototype terminé : {} blocs",placed);}
    }
    static void put(Queue<Placement> q,BlockPos p,BlockState s){q.add(new Placement(p.immutable(),s));}
}
