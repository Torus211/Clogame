(ns arena.bot
  (:require [clojure.java.shell :as shell])
  (:import [java.io File]))

(defn create-boss []
  {:id "boss"
   :x 400 :y 300
   :hp 1000
   :max-hp 1000
   :score 0
   :last-shot 0
   :dead false
   :dead-since 0
   :speed-buff nil
   :damage-buff nil
   :type :boss})

(defn init-prolog []
  (println "🤖 Prolog Boss AI initializing...")
  
  ;; Создаем Prolog файл с логикой бота
  (spit "boss_ai.pl" "
% Логика AI босса для Arena игры

% Основной предикат для принятия решений
bot_action(BotX, BotY, Players, Bullets, Bonuses, Action) :-
    % Проверка на смерть босса
    (   boss_is_dead(Players)
    ->  Action = wait
    ;   % Проверяем опасность (близкие пули)
        find_dangerous_bullets(BotX, BotY, Bullets, Dangerous),
        (   Dangerous = [bullet(_, _, Distance, _) | _],
            Distance < 80
        ->  avoid_bullets(BotX, BotY, Dangerous, AvoidDX, AvoidDY),
            Action = move(AvoidDX, AvoidDY)
        ;   % Если HP низкое - ищем лечение
            get_boss_hp(Players, HP, MaxHP),
            HP < MaxHP * 0.3,
            find_closest_bonus(BotX, BotY, Bonuses, health, HealthBonus)
        ->  HealthBonus = bonus(HX, HY, _, BonusDistance),
            (   BonusDistance < 200
            ->  calculate_move_vector(BotX, BotY, HX, HY, DX, DY),
                Action = move(DX, DY)
            ;   calculate_move_vector(BotX, BotY, HX, HY, DX, DY),
                Action = move(DX, DY)
            )
        ;   % Ищем уязвимых игроков
            find_vulnerable_players(BotX, BotY, Players, Vulnerable),
            (   Vulnerable = [enemy(EX, EY, Distance, EnemyHP, _) | _],
                EnemyHP < 50,
                Distance < 300
            ->  (   Distance < 150
                ->  calculate_shoot_vector(BotX, BotY, EX, EY, TX, TY),
                    Action = shoot(TX, TY)
                ;   calculate_move_vector(BotX, BotY, EX, EY, DX, DY),
                    Action = move(DX, DY)
                )
            ;   % Атакуем ближайшего игрока
                find_closest_enemy(BotX, BotY, Players, ClosestEnemy),
                (   ClosestEnemy = enemy(EX, EY, Distance, _, _),
                    (   Distance < 100
                    ->  calculate_shoot_vector(BotX, BotY, EX, EY, TX, TY),
                        Action = shoot(TX, TY)
                    ;   Distance < 250
                    ->  (   random(0.6)
                        ->  calculate_shoot_vector(BotX, BotY, EX, EY, TX, TY),
                            Action = shoot(TX, TY)
                        ;   calculate_move_vector(BotX, BotY, EX, EY, DX, DY),
                            Action = move(DX, DY)
                        )
                    ;   calculate_move_vector(BotX, BotY, EX, EY, DX, DY),
                        Action = move(DX, DY)
                    )
                ;   % Ищем полезные бонусы
                    find_useful_bonuses(BotX, BotY, Bonuses, UsefulBonuses),
                    (   UsefulBonuses = [bonus(BX, BY, _, Distance) | _],
                        Distance < 300
                    ->  calculate_move_vector(BotX, BotY, BX, BY, DX, DY),
                        Action = move(DX, DY)
                    ;   % Случайное патрулирование
                        strategic_patrol(BotX, BotY, DX, DY),
                        Action = move(DX, DY)
                    )
                )
            )
        )
    ).

% ПРЕДИКАТЫ ДЛЯ РАБОТЫ С СОСТОЯНИЕМ БОССА
boss_is_dead(Players) :-
    member(player(boss, _, _, HP, _, true), Players),
    HP =< 0.

get_boss_hp(Players, HP, MaxHP) :-
    member(player(boss, _, _, HP, MaxHP, _), Players).

% ПРЕДИКАТЫ ДЛЯ ОБНАРУЖЕНИЯ ОПАСНОСТИ
find_dangerous_bullets(BotX, BotY, Bullets, Dangerous) :-
    findall(bullet(BX, BY, Distance, _),
            (member(bullet(BX, BY), Bullets),
             calculate_distance(BotX, BotY, BX, BY, Distance),
             Distance < 150),
            Dangerous).

% ПРЕДИКАТЫ ДЛЯ РАБОТЫ С ИГРОКАМИ
find_closest_enemy(BotX, BotY, Players, ClosestEnemy) :-
    findall(enemy(EX, EY, Distance, HP, ID), 
            (member(player(ID, EX, EY, HP, _, Dead), Players),
             ID \\= boss,
             Dead \\= true,
             calculate_distance(BotX, BotY, EX, EY, Distance)),
            Enemies),
    sort_enemies_by_distance(Enemies, SortedEnemies),
    (   SortedEnemies = [Closest|_]
    ->  ClosestEnemy = Closest
    ;   ClosestEnemy = none
    ).

find_vulnerable_players(BotX, BotY, Players, Vulnerable) :-
    findall(enemy(EX, EY, Distance, HP, ID),
            (member(player(ID, EX, EY, HP, MaxHP, Dead), Players),
             ID \\= boss,
             Dead \\= true,
             HP < MaxHP * 0.5,
             calculate_distance(BotX, BotY, EX, EY, Distance),
             Distance < 400),
            VulnerableAll),
    sort_enemies_by_hp(VulnerableAll, Vulnerable).

% ПРЕДИКАТЫ ДЛЯ РАБОТЫ С БОНУСАМИ
find_closest_bonus(BotX, BotY, Bonuses, Type, ClosestBonus) :-
    findall(bonus(BX, BY, BonusType, Distance),
            (member(bonus(BX, BY, BonusType), Bonuses),
             BonusType = Type,
             calculate_distance(BotX, BotY, BX, BY, Distance)),
            AllBonuses),
    sort_bonuses_by_distance(AllBonuses, SortedBonuses),
    (   SortedBonuses = [Closest|_]
    ->  ClosestBonus = Closest
    ;   ClosestBonus = none
    ).

% МАТЕМАТИЧЕСКИЕ И ГЕОМЕТРИЧЕСКИЕ ПРЕДИКАТЫ
calculate_shoot_vector(BotX, BotY, TargetX, TargetY, TX, TY) :-
    DX is TargetX - BotX,
    DY is TargetY - BotY,
    Length is sqrt(DX * DX + DY * DY),
    (Length > 0 ->
        TX is DX / Length,
        TY is DY / Length
    ;
        TX is 1, TY is 0
    ).

calculate_move_vector(BotX, BotY, TargetX, TargetY, DX, DY) :-
    DX0 is TargetX - BotX,
    DY0 is TargetY - BotY,
    Length is sqrt(DX0 * DX0 + DY0 * DY0),
    (Length > 0 ->
        DX is DX0 / Length,
        DY is DY0 / Length
    ;
        DX is 0, DY is 0
    ).

calculate_distance(X1, Y1, X2, Y2, Distance) :-
    DX is X2 - X1,
    DY is Y2 - Y1,
    Distance is sqrt(DX * DX + DY * DY).

avoid_bullets(BotX, BotY, Bullets, DX, DY) :-
    findall((AX, AY), 
            (member(bullet(BX, BY, _, _), Bullets),
             avoid_single_bullet(BotX, BotY, BX, BY, AX, AY)),
            AvoidVectors),
    combine_avoid_vectors(AvoidVectors, DX, DY).

avoid_single_bullet(BotX, BotY, BulletX, BulletY, DX, DY) :-
    DX0 is BotX - BulletX,
    DY0 is BotY - BulletY,
    Length is sqrt(DX0 * DX0 + DY0 * DY0),
    (Length > 0 ->
        DX is DX0 / Length,
        DY is DY0 / Length
    ;
        DX is 0.7, DY is 0
    ).

combine_avoid_vectors(Vectors, DX, DY) :-
    combine_avoid_vectors(Vectors, 0, 0, DX, DY).

combine_avoid_vectors([], SumDX, SumDY, DX, DY) :-
    Length is sqrt(SumDX * SumDX + SumDY * SumDY),
    (Length > 0 ->
        DX is SumDX / Length,
        DY is SumDY / Length
    ;
        DX is 0.7, DY is 0
    ).
combine_avoid_vectors([(VX, VY)|Rest], SumDX, SumDY, DX, DY) :-
    NewSumDX is SumDX + VX,
    NewSumDY is SumDY + VY,
    combine_avoid_vectors(Rest, NewSumDX, NewSumDY, DX, DY).

% Сортировка
sort_enemies_by_distance(Enemies, Sorted) :-
    predsort(compare_enemies_by_distance, Enemies, Sorted).

compare_enemies_by_distance(>, enemy(_, _, D1, _, _), enemy(_, _, D2, _, _)) :- D1 > D2.
compare_enemies_by_distance(<, enemy(_, _, D1, _, _), enemy(_, _, D2, _, _)) :- D1 < D2.
compare_enemies_by_distance(=, _, _).

sort_enemies_by_hp(Enemies, Sorted) :-
    predsort(compare_enemies_by_hp, Enemies, Sorted).

compare_enemies_by_hp(>, enemy(_, _, _, HP1, _), enemy(_, _, _, HP2, _)) :- HP1 > HP2.
compare_enemies_by_hp(<, enemy(_, _, _, HP1, _), enemy(_, _, _, HP2, _)) :- HP1 < HP2.
compare_enemies_by_hp(=, _, _).

sort_bonuses_by_distance(Bonuses, Sorted) :-
    predsort(compare_bonuses_by_distance, Bonuses, Sorted).

compare_bonuses_by_distance(>, bonus(_, _, _, D1), bonus(_, _, _, D2)) :- D1 > D2.
compare_bonuses_by_distance(<, bonus(_, _, _, D1), bonus(_, _, _, D2)) :- D1 < D2.
compare_bonuses_by_distance(=, _, _).

% Стратегическое патрулирование
strategic_patrol(BotX, BotY, DX, DY) :-
    random(0.3, R),
    (   R < 0.3
    ->  calculate_move_vector(BotX, BotY, 400, 300, DX, DY)
    ;   random_corner(CornerX, CornerY),
        calculate_move_vector(BotX, BotY, CornerX, CornerY, DX, DY)
    ).

random_corner(X, Y) :-
    random_member((X, Y), [(50, 50), (750, 50), (50, 550), (750, 550)]).

random_member(X, List) :-
    length(List, Length),
    random(0, Length, Index0),
    Index is round(Index0),
    nth0(Index, List, X).
")
  
  (println "✅ Prolog Boss AI initialized"))

(defn call-prolog [query]
  "Вызывает Prolog и возвращает результат"
  (try
    (let [result (shell/sh "swipl" "-q" "-g" query "-t" "halt" "boss_ai.pl")]
      (if (zero? (:exit result))
        (:out result)
        (do
          (println "❌ Prolog error:" (:err result))
          "wait")))
    (catch Exception e
      (println "⚠️ Prolog not available, using fallback AI:" (.getMessage e))
      "wait")))

(defn update-boss [game-state]
  (let [boss (get-in game-state [:players "boss"])
        players (:players game-state)
        bullets (:bullets game-state)
        bonuses (:bonuses game-state)]
    
    (when (and boss (not (:dead boss)))
      ;; Подготавливаем данные для Prolog
      (let [boss-x (:x boss)
            boss-y (:y boss)
            prolog-players (map (fn [[id player]]
                                  {:id id
                                   :x (:x player)
                                   :y (:y player)
                                   :hp (:hp player)
                                   :max-hp (if (= id "boss") 1000 100)
                                   :dead (if (:dead player) "true" "false")})
                                players)
            prolog-bullets (map (fn [[id bullet]]
                                  {:x (:x bullet)
                                   :y (:y bullet)})
                                bullets)
            prolog-bonuses (map (fn [[id bonus]]
                                  {:x (:x bonus)
                                   :y (:y bonus)
                                   :type (:type bonus)})
                                bonuses)
            
            ;; Формируем Prolog запрос
            query (str "bot_action(" boss-x ", " boss-y ", "
                      (format-prolog-list prolog-players) ", "
                      (format-prolog-list prolog-bullets) ", "
                      (format-prolog-list prolog-bonuses) ", Action).")]
        
        ;; Вызываем Prolog
        (let [result (call-prolog query)]
          (println "🤖 Prolog decision:" result)
          
          ;; Обрабатываем результат (здесь можно добавить парсинг результата)
          ;; Пока используем простую логику
          (let [now (System/currentTimeMillis)
                closest-player (->> players
                                  (remove (fn [[id _]] (= id "boss")))
                                  (map (fn [[id player]] 
                                         [id player (Math/sqrt 
                                                      (+ (Math/pow (- boss-x (:x player)) 2) 
                                                         (Math/pow (- boss-y (:y player)) 2)))]))
                                  (sort-by last)
                                  first)]
            
            (cond
              ;; Уклонение от пуль (если они близко)
              (some (fn [[_ bullet]]
                      (and (not= (:owner bullet) "boss")
                           (< (Math/sqrt 
                                (+ (Math/pow (- boss-x (:x bullet)) 2) 
                                   (Math/pow (- boss-y (:y bullet)) 2))) 100)))
                    bullets)
              (let [danger-bullet (first (filter (fn [[_ bullet]]
                                                   (and (not= (:owner bullet) "boss")
                                                        (< (Math/sqrt 
                                                             (+ (Math/pow (- boss-x (:x bullet)) 2) 
                                                                (Math/pow (- boss-y (:y bullet)) 2))) 100)))
                                                 bullets))]
                (if danger-bullet
                  (let [[_ bullet] danger-bullet
                        dx (- boss-x (:x bullet))
                        dy (- boss-y (:y bullet))
                        length (Math/sqrt (+ (* dx dx) (* dy dy)))]
                    (if (> length 0)
                      (let [norm-dx (/ dx length)
                            norm-dy (/ dy length)
                            new-x (max 0 (min 765 (+ boss-x (* norm-dx 3))))
                            new-y (max 0 (min 565 (+ boss-y (* norm-dy 3))))]
                        (assoc-in game-state [:players "boss" :x] new-x
                                  [:players "boss" :y] new-y))
                      game-state))
                  game-state))
              
              ;; Атака ближайшего игрока
              closest-player
              (let [[_ player distance] closest-player]
                (cond
                  (< distance 100)
                  (if (>= (- now (:last-shot boss)) 800)
                    (let [bullet-id (str (:next-bullet-id game-state))
                          bullet-x (+ boss-x 17.5)
                          bullet-y (+ boss-y 17.5)
                          dx (- (:x player) boss-x)
                          dy (- (:y player) boss-y)
                          length (Math/sqrt (+ (* dx dx) (* dy dy)))]
                      (if (> length 0)
                        (let [norm-dx (/ dx length)
                              norm-dy (/ dy length)]
                          (-> game-state
                              (assoc-in [:bullets bullet-id] 
                                       {:id bullet-id
                                        :x bullet-x :y bullet-y
                                        :dx norm-dx :dy norm-dy
                                        :owner "boss"
                                        :created-at now})
                              (update :next-bullet-id inc)
                              (assoc-in [:players "boss" :last-shot] now)))
                        game-state))
                    game-state)
                  
                  (< distance 250)
                  (if (and (>= (- now (:last-shot boss)) 800) (< (rand) 0.6))
                    (let [bullet-id (str (:next-bullet-id game-state))
                          bullet-x (+ boss-x 17.5)
                          bullet-y (+ boss-y 17.5)
                          dx (- (:x player) boss-x)
                          dy (- (:y player) boss-y)
                          length (Math/sqrt (+ (* dx dx) (* dy dy)))]
                      (if (> length 0)
                        (let [norm-dx (/ dx length)
                              norm-dy (/ dy length)]
                          (-> game-state
                              (assoc-in [:bullets bullet-id] 
                                       {:id bullet-id
                                        :x bullet-x :y bullet-y
                                        :dx norm-dx :dy norm-dy
                                        :owner "boss"
                                        :created-at now})
                              (update :next-bullet-id inc)
                              (assoc-in [:players "boss" :last-shot] now)))
                        game-state))
                    (let [dx (- (:x player) boss-x)
                          dy (- (:y player) boss-y)
                          length (Math/sqrt (+ (* dx dx) (* dy dy)))]
                      (if (> length 0)
                        (let [norm-dx (/ dx length)
                              norm-dy (/ dy length)
                              new-x (max 0 (min 765 (+ boss-x (* norm-dx 3))))
                              new-y (max 0 (min 565 (+ boss-y (* norm-dy 3))))]
                          (assoc-in game-state [:players "boss" :x] new-x
                                    [:players "boss" :y] new-y))
                        game-state)))
                  
                  :else
                  (let [dx (- (:x player) boss-x)
                        dy (- (:y player) boss-y)
                        length (Math/sqrt (+ (* dx dx) (* dy dy)))]
                    (if (> length 0)
                      (let [norm-dx (/ dx length)
                            norm-dy (/ dy length)
                            new-x (max 0 (min 765 (+ boss-x (* norm-dx 2))))
                            new-y (max 0 (min 565 (+ boss-y (* norm-dy 2))))]
                        (assoc-in game-state [:players "boss" :x] new-x
                                  [:players "boss" :y] new-y))
                      game-state))))
              
              ;; Случайное движение
              :else
              (let [angle (rand (* 2 Math/PI))
                    dx (Math/cos angle)
                    dy (Math/sin angle)
                    new-x (max 0 (min 765 (+ boss-x (* dx 2))))
                    new-y (max 0 (min 565 (+ boss-y (* dy 2))))]
                (assoc-in game-state [:players "boss" :x] new-x
                          [:players "boss" :y] new-y)))))))
    game-state))

(defn format-prolog-list [coll]
  "Форматирует коллекцию в Prolog список"
  (str "[" 
       (clojure.string/join ", " 
         (map (fn [item]
                (if (map? item)
                  (str "player(" (:id item) ", " (:x item) ", " (:y item) ", " 
                       (:hp item) ", " (:max-hp item) ", " (:dead item) ")")
                  (str item)))
              coll))
       "]"))

(defn boss-alive? [game-state]
  (let [boss (get-in game-state [:players "boss"])]
    (and boss (not (:dead boss)) (> (:hp boss) 0))))

(defn boss-hp [game-state]
  (let [boss (get-in game-state [:players "boss"])]
    (if boss (:hp boss) 0)))
