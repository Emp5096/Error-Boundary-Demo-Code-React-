package com.xy.interview.demo;

/**
 * @Auther: cxy
 * @Date: 2026/7/7 - 07 - 07 - 20:53
 * @Description: com.xy.interview.demo
 * @version: 1.0
 */
public class SkipListTest {
    class Node{
        int value;
        Node[] forward;

        Node(int value, int level){
            this.value = value;
            this.forward = new Node[level];
        }
    }
    private static final int MAX_LEVEL = 16;
    private static final double P = 0.5;

    private Node head = new Node(Integer.MIN_VALUE, MAX_LEVEL);
    private int currentLevel = 1;
    private int randomLevel(){
        int level = 1;
        while(Math.random() < P && level < MAX_LEVEL){
            level++;
        }
        return level;
    }

    public boolean search(int target){
        Node cur = head;

        for(int i = currentLevel - 1; i > 0; i --){
            while(cur.forward[i] != null && cur.forward[i].value < target){
                cur = cur.forward[i];
            }
        }
        cur = cur.forward[0];

        return cur != null && cur.value == target;

    }

    public void insert(int value){
        Node[] update = new Node[MAX_LEVEL];
        Node cur = head;

        //找出记录前驱节点
        for (int i = currentLevel; i > 0 ; i--) {
            while (cur.forward[i] != null && cur.forward[i].value < value){
                cur = cur.forward[i];
            }
            update[i] = cur;
        }

        //抛硬币看有几层
        int newLevel = randomLevel();

        //若是大于现在的最大层，则需要将head安排上
        if (newLevel > currentLevel){
            for (int i = currentLevel; i < newLevel; i ++){
                update[i] = head;
            }
            currentLevel = newLevel;
        }

        //创建插入点
        Node newNode = new Node(value, newLevel);

        //链路插入插入点
        for (int i = 0; i < newLevel; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }

    }

    public void delete(int value){
        Node[] update = new Node[MAX_LEVEL];
        Node cur = head;

        //找出记录前驱节点
        for (int i = currentLevel; i > 0 ; i--) {
            while (cur.forward[i] != null && cur.forward[i].value < value){
                cur = cur.forward[i];
            }
            update[i] = cur;
        }

        //最低一层，即完整链条有没有存在目标value
        cur = cur.forward[0];
        if (cur == null || cur.value != value){
            return;
        }

        //存在，那么就绕过当前节点，否则就跳过循环
        for (int i = 0; i < currentLevel; i++){
            if (update[i].forward[i] != cur){
                break;
            }
            update[i].forward[i] = cur.forward[i];
        }
        //若是出现层级因为删除，不存在节点，那就删除层
        while (currentLevel > 1 && head.forward[currentLevel - 1] == null){
            currentLevel--;
        }
    }
}
