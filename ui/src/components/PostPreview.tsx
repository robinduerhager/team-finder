import * as React from "react";
import cx from "classnames";
import { Post } from "../model/post";
import { Button } from "./Button";
import { useState } from "react";
import { PostModal } from "./PostModal";
import { SkillList } from "./SkillList";

interface Props {
  post: Post;
  className?: string;
  adminView?: boolean;
  deletePost?: (post: Post) => void;
}

export const PostPreview: React.FC<Props> = ({
  post,
  className,
  adminView,
  deletePost,
}) => {
  const [isModelOpen, setIsModelOpen] = useState(false);

  return (
    <article
      id={"post-" + post.id}
      className={cx(
        "border-2 border-white p-2 grid grid-flow-row auto-cols-auto gap-y-2",
        className
      )}
    >
      <h3 className="font-bold text-xl">{post.title}</h3>
      <SkillList
        label="Looking for:"
        skills={post.skillsSought}
        className="[--skill-color:theme(colors.accent1)]"
      />
      <SkillList
        label="Brings:"
        skills={post.skillsPossessed}
        className="[--skill-color:theme(colors.accent2)]"
      />
      <p>{post.description}</p>

      <PostModal
        post={post}
        isModalOpen={isModelOpen}
        setIsModalOpen={setIsModelOpen}
      />
      <div className={`flex ${adminView ? "justify-between" : "justify-end"}`}>
        {adminView && (
          <Button
            style={{ backgroundColor: "red" }}
            onClick={() => deletePost!(post)}
          >
            Delete
          </Button>
        )}
        <Button
          className="justify-self-end"
          onClick={() => setIsModelOpen(true)}
        >
          More
        </Button>
      </div>
    </article>
  );
};
