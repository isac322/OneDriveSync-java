package org.onedrive.container.items;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpMethod;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.onedrive.Client;
import org.onedrive.container.IdentitySet;
import org.onedrive.container.facet.*;
import org.onedrive.container.items.pointer.IdPointer;
import org.onedrive.container.items.pointer.PathPointer;
import org.onedrive.exceptions.ErrorResponseException;
import org.onedrive.exceptions.InternalException;
import org.onedrive.exceptions.InvalidJsonException;
import org.onedrive.network.async.ResponseFuture;
import org.onedrive.network.async.ResponseFutureListener;
import org.onedrive.utils.DirectByteInputStream;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * {@// TODO: Enhance javadoc }
 *
 * @author <a href="mailto:yoobyeonghun@gmail.com" target="_top">isac322</a>
 */
@JsonDeserialize(as = FolderItem.class)
public class FolderItem extends BaseItem implements Iterable<BaseItem> {
	@NotNull protected FolderFacet folder;
	@Nullable @JsonProperty protected SpecialFolderFacet specialFolder;
	@Nullable @JsonIgnore protected List<FolderItem> folderChildren;
	@Nullable @JsonIgnore protected List<FileItem> fileChildren;
	@Nullable @JsonIgnore protected List<BaseItem> allChildren;
	@Nullable @JsonProperty protected ObjectNode root;

	/**
	 * @throws IllegalArgumentException It's because of construction of {@link IdPointer} or {@link PathPointer}. or
	 *                                  if parameter {@code parentReference} is null even if this isn't root directory.
	 * @see IdPointer#IdPointer(String, String)
	 * @see IdPointer#IdPointer(String)
	 * @see PathPointer#PathPointer(String, String)
	 */
	@JsonCreator
	protected FolderItem(@JacksonInject("OneDriveClient") Client client,
						 @JsonProperty("id") @NotNull String id,
						 @JsonProperty("createdBy") IdentitySet createdBy,
						 @JsonProperty("createdDateTime") String createdDateTime,
						 @JsonProperty("cTag") String cTag,
						 @JsonProperty("deleted") ObjectNode deleted,
						 @JsonProperty("description") String description,
						 @JsonProperty("eTag") String eTag,
						 @JsonProperty("fileSystemInfo") FileSystemInfoFacet fileSystemInfo,
						 @JsonProperty("folder") @NotNull FolderFacet folder,
						 @JsonProperty("lastModifiedBy") IdentitySet lastModifiedBy,
						 @JsonProperty("lastModifiedDateTime") String lastModifiedDateTime,
						 @JsonProperty("name") @NotNull String name,
						 @JsonProperty("parentReference") @Nullable ItemReference parentReference,
						 @JsonProperty("root") @Nullable ObjectNode root,
						 @JsonProperty("searchResult") @Nullable SearchResultFacet searchResult,
						 @JsonProperty("shared") @Nullable SharedFacet shared,
						 @JsonProperty("sharePointIds") @Nullable SharePointIdsFacet sharePointIds,
						 @JsonProperty("size") Long size,
						 @JsonProperty("specialFolder") @Nullable SpecialFolderFacet specialFolder,
						 @JsonProperty("webDavUrl") String webDavUrl,
						 @JsonProperty("webUrl") String webUrl,
						 @JsonProperty("children@odata.nextLink") @Nullable String nextLink,
						 @JsonProperty("children") @Nullable ArrayNode children) {
		super(client, id, createdBy, createdDateTime, cTag, deleted, description, eTag, fileSystemInfo,
				lastModifiedBy, lastModifiedDateTime, name, parentReference, searchResult, shared, sharePointIds,
				size, webDavUrl, webUrl);

		this.folder = folder;
		this.specialFolder = specialFolder;
		this.root = root;

		if (children != null) {
			this.folderChildren = new CopyOnWriteArrayList<>();
			this.fileChildren = new CopyOnWriteArrayList<>();
			this.allChildren = new CopyOnWriteArrayList<>();

			parseChildren(client, children, nextLink, allChildren, folderChildren, fileChildren);
		}
		else {
			folderChildren = null;
			fileChildren = null;
			allChildren = null;
		}

		if (isRoot()) {
			assert pathPointer == null : "`pathPointer` isn't null in FolderItem";
			assert parentReference == null : "`parentReference` isn't null in FolderItem";
			pathPointer = new PathPointer("/", getDriveId());
		}
		else {
			assert parentReference != null : "FolderItem that not root dir can't have null `parentReference` argument";
		}
	}

	protected static void addChildren(@NotNull Client client, @NotNull JsonNode array, @NotNull List<BaseItem> all,
									  @NotNull List<FolderItem> folder, @NotNull List<FileItem> file) {
		for (JsonNode child : array) {
			if (!child.isObject())
				// if child isn't object
				throw new InvalidJsonException("Response isn't object in JSON. response : " + child);

			BaseItem item = client.mapper().convertValue(child, BaseItem.class);

			if (item instanceof FolderItem) {
				folder.add((FolderItem) item);
			}
			else if (item instanceof FileItem) {
				file.add((FileItem) item);
			}
			else {
				// if child is neither FolderItem nor FileItem nor PackageItem.
				assert item instanceof PackageItem : "Wrong item type";
			}
			all.add(item);
		}
	}

	@SneakyThrows(URISyntaxException.class)
	protected static void parseChildren(@NotNull final Client client, @NotNull JsonNode array,
										@Nullable String nextLink, @NotNull List<BaseItem> all,
										@NotNull List<FolderItem> folder, @NotNull List<FileItem> file) {
		final ObjectNode jsonObject[] = new ObjectNode[1];
		while (nextLink != null) {
			final CountDownLatch latch = new CountDownLatch(1);

			ResponseFuture responseFuture = client.requestTool().doAsync(
					HttpMethod.GET,
					new URI(nextLink),
					new ResponseFutureListener() {
						@Override public void operationComplete(ResponseFuture future) throws Exception {
							DirectByteInputStream result = future.get();

							try {
								jsonObject[0] = (ObjectNode) client.mapper().readTree(result);
							}
							catch (JsonProcessingException e) {
								throw new InvalidJsonException(
										e,
										future.response().status().code(),
										result.rawBuffer());
							}
							catch (IOException e) {
								// FIXME: custom exception
								throw new RuntimeException("DEV: Unrecognizable json response.", e);
							}
							latch.countDown();
						}
					});

			addChildren(client, array, all, folder, file);

			// responseFuture.syncUninterruptibly();
			try {
				latch.await();
			}
			catch (InterruptedException e) {
				throw new InternalException("Exception occurs while waiting lock in BaseItem#update()", e);
			}

			if (jsonObject[0].has("@odata.nextLink"))
				nextLink = jsonObject[0].get("@odata.nextLink").asText();
			else
				nextLink = null;

			array = jsonObject[0].get("value");
		}

		addChildren(client, array, all, folder, file);
	}

	protected void fetchChildren() throws ErrorResponseException {
		_fetchChildren(Client.ITEM_ID_PREFIX + id + "/children");
	}

	protected void _fetchChildren(String url) throws ErrorResponseException {
		ObjectNode content = client.requestTool().doGetJson(url);

		allChildren = new CopyOnWriteArrayList<>();
		folderChildren = new CopyOnWriteArrayList<>();
		fileChildren = new CopyOnWriteArrayList<>();

		JsonNode value = content.get("value");
		JsonNode nextLink = content.get("@odata.nextLink");

		// TODO: if-none-match request header handling.
		// TODO: not 200 OK response handling.
		parseChildren(client, value, nextLink == null ? null : nextLink.asText(),
				allChildren, folderChildren, fileChildren);
	}


	/**
	 * Implementation of <a href='https://dev.onedrive.com/items/create.htm'>detail</a>.
	 * <p>
	 * {@// TODO: Enhance javadoc }
	 * {@// TODO: Implement '@name.conflictBehavior' }
	 *
	 * @param name New folder name.
	 *
	 * @return New folder's object.
	 *
	 * @throws RuntimeException If creating folder or converting response is fails.
	 *                          {@// TODO: add more @throws }
	 */
	@NotNull
	public FolderItem createFolder(@NotNull String name) throws ErrorResponseException {
		return client.createFolder(this.id, name);
	}


	/**
	 * Update data itself with {@code newItem}.<br>
	 * This will <b>set all children {@code List} null</b>. so very next call that related with children may take a
	 * while to
	 * fetch children data.
	 *
	 * @param newItem New object that contains new data to update.
	 *
	 * @throws IllegalArgumentException It's because of construction of {@link IdPointer} or {@link PathPointer}.
	 * @throws InternalException        if parameter {@code parentReference} is null even if this isn't root directory.
	 */
	@Override
	protected void refreshBy(@NotNull BaseItem newItem) {
		super.refreshBy(newItem);

		FolderItem item = (FolderItem) newItem;

		this.folder = item.folder;
		this.specialFolder = item.specialFolder;

		folderChildren = null;
		fileChildren = null;
		allChildren = null;

		if (isRoot()) {
			assert parentReference == null : "`parentReference` isn't null in FolderItem";
			pathPointer = new PathPointer("/", getDriveId());
		}
		else {
			assert parentReference != null : "FolderItem that not root dir can't have null `parentReference` argument";
		}
	}


	/*
	*************************************************************
	*
	* Custom Getter
	*
	*************************************************************
	 */


	@JsonIgnore
	public boolean isRoot() {
		return root != null;
	}

	@JsonIgnore
	public boolean isChildrenFetched() {
		return allChildren != null && folderChildren != null && fileChildren != null;
	}

	@JsonIgnore
	public boolean isSpecial() {
		return specialFolder != null;
	}

	public long childrenCount() {
		return folder.getChildCount();
	}

	@NotNull
	@Override
	public String getDriveId() {
		if (isRoot()) return id.split("!")[0];
		return parentReference.driveId;
	}

	@NotNull
	public List<BaseItem> allChildren() throws ErrorResponseException {
		if (!isChildrenFetched()) fetchChildren();
		return allChildren;
	}

	@NotNull
	public List<FolderItem> folderChildren() throws ErrorResponseException {
		if (!isChildrenFetched()) fetchChildren();
		return folderChildren;
	}

	@NotNull
	public List<FileItem> fileChildren() throws ErrorResponseException {
		if (!isChildrenFetched()) fetchChildren();
		return fileChildren;
	}


	/*
	*************************************************************
	*
	* Custom Iterator
	*
	*************************************************************
	 */


	@NotNull
	@Override
	public Iterator<BaseItem> iterator() {
		try {
			return new ChildrenIterator(allChildren().iterator());
		}
		catch (ErrorResponseException e) {
			// FIXME: custom exception
			throw new RuntimeException(e);
		}
	}

	private class ChildrenIterator implements Iterator<BaseItem> {
		private final Iterator<BaseItem> itemIterator;

		ChildrenIterator(Iterator<BaseItem> iterator) {
			this.itemIterator = iterator;
		}

		@Override
		public boolean hasNext() {
			return itemIterator.hasNext();
		}

		@Override
		public BaseItem next() {
			return itemIterator.next();
		}

		@Override
		public void remove() {
			itemIterator.remove();
		}
	}
}