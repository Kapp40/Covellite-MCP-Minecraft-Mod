package covellite.core;

import java.util.List;
import java.util.Map;

/**
 * Bridge between the verified core and the Minecraft client. The NeoForge mod
 * implements this interface; the core uses it only through this contract so
 * that core tests never need Minecraft classes.
 */
public interface GameAdapter {

    // Perception
    Map<String, Object> getPlayerState();
    Map<String, Object> getWorldInfo();
    List<Map<String, Object>> getPlayerList();
    Map<String, Object> getInventory();
    Map<String, Object> getEquipment();
    List<Map<String, Object>> getNearbyEntities(double radius, int limit);
    List<Map<String, Object>> getNearbyBlocks(double radius, List<String> filters, int limit);
    Map<String, Object> getTargetedObject();
    Map<String, Object> getEntityInfo(String uuidOrName);
    Map<String, Object> getBlockInfo(int x, int y, int z);
    Map<String, Object> getChatHistory(int limit);
    List<Map<String, Object>> getRecentEvents(long since, int limit);

    // Movement / control
    String move(double forward, double strafe, boolean sprint, int ticks);
    String look(double yaw, double pitch);
    String lookAt(double x, double y, double z);
    String jump();
    String setSneak(boolean enabled);
    String stop();
    String follow(String playerName, double distance);
    String goTo(double x, double y, double z, double tolerance);
    String emergencyStop();

    // Communication
    void sendChat(String message);

    // Action status
    Map<String, Object> getActionStatus(String id);
    boolean cancelAction(String id);
}
