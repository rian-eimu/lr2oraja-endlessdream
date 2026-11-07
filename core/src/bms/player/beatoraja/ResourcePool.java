package bms.player.beatoraja;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.ToLongFunction;
import java.util.function.Supplier;

import com.badlogic.gdx.utils.Disposable;

/**
 * リソースプール。イメージデータやオーディオデータ等の読み込みコストが大きく、
 * なおかつ明示的な解放が必要なリソースをプールする仕組みを提供する。
 * 
 * @author exch
 *
 * @param <K> リソースを取り出すためのキー
 * @param <V> リソース
 */
public abstract class ResourcePool<K, V> implements Disposable {
	/** プールが現在保持しているリソースに関する軽量診断情報。 */
	public record Statistics(int resourceCount, long estimatedBytes) {
	}
	/**
	 * リソースの最大世代数
	 */
	private final int maxgen;
	/**
	 * リソース
	 */
	private final ConcurrentHashMap<K, ResourceCacheElement<V>> resourceMap = new ConcurrentHashMap<>();
	// 読み込みは並列実行可能だが、破棄処理は全読み込みの登録完了まで待機する必要がある
	private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

	public ResourcePool(int maxgen) {
		this.maxgen = maxgen;
	}

	/**
	 * 指定するキーの要素が存在する場合はtrueを返す
	 *
	 * @param key リソースのキー
	 * @return キーに対応するリソースが存在する場合はtrue
	 */
	public boolean exists(K key) {
		lifecycleLock.readLock().lock();
		try {
			return resourceMap.containsKey(key);
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/**
	 * 指定したキーに対応するリソースを取得する。リソースがプールにない場合はloadを呼び出して
	 * リソースを取得し、プールに登録した上でリソースを返す。
	 *
	 * @param key リソースのキー
	 * @return リソース。読めなかった場合はnullを返す
	 */
 	public V get(K key) {
		return get(key, null);
	}

	/** 呼び出し元指定のファクトリを用いてリソースを読み込む（ファクトリ自体はプールに保持しない）。 */
	public V get(K key, Supplier<? extends V> factory) {
		lifecycleLock.readLock().lock();
		try {
			ResourceCacheElement<V> element = resourceMap.get(key);
			if (element == null) {
				element = resourceMap.computeIfAbsent(key, k -> {
					V resource = factory != null ? factory.get() : load(k);
					return resource != null ? new ResourceCacheElement<>(resource) : null;
				});
			}
			if (element != null) {
				element.gen = 0;
			}
			return element != null ? element.resource : null;
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

 	/**
 	 * 世代数を進め、最大世代数を経過したリソースを開放する
 	 */
	public void disposeOld() {
		lifecycleLock.writeLock().lock();
		try {
			var iterator = resourceMap.values().iterator();
			while (iterator.hasNext()) {
				ResourceCacheElement<V> element = iterator.next();
				if (element.gen == maxgen) {
					iterator.remove();
					dispose(element.resource);
				} else {
					element.gen++;
				}
			}
		} finally {
			lifecycleLock.writeLock().unlock();
		}
	}

	/**
	 * 現在のリソースの要素数を返す。
	 * @return リソースの要素数
	 */
	public int size() {
		lifecycleLock.readLock().lock();
		try {
			return resourceMap.size();
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/**
	 * プール状態の時点サマリを返す。
	 */
	public Statistics getStatistics(ToLongFunction<? super V> sizeEstimator) {
		lifecycleLock.readLock().lock();
		try {
			long estimatedBytes = 0;
			for (ResourceCacheElement<V> element : resourceMap.values()) {
				estimatedBytes += Math.max(0, sizeEstimator.applyAsLong(element.resource));
			}
			return new Statistics(resourceMap.size(), estimatedBytes);
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}
	
	public void dispose() {
		lifecycleLock.writeLock().lock();
		try {
			var iterator = resourceMap.values().iterator();
			while (iterator.hasNext()) {
				ResourceCacheElement<V> element = iterator.next();
				iterator.remove();
				dispose(element.resource);
			}
		} finally {
			lifecycleLock.writeLock().unlock();
		}
	}
	
	/**
	 * リソースを読み込む
	 * @param key リソースのキー
	 * @return リソース。読めなかった場合はnullを返す
	 */
	protected abstract V load(K key);

	/**
	 * リソースを開放する
	 * @param resource 開放するリソース
	 */
	protected abstract void dispose(V resource);

	/**
	 * リソース
	 *
	 * @param <R>
	 */
	private static class ResourceCacheElement<R> {
		/**
		 * リソース
		 */
		public final R resource;
		/**
		 * 世代
		 */
		public int gen;
		
		public ResourceCacheElement(R resource) {
			this.resource = resource;
		}
	}
}
