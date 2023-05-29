package demo.entity;



import orm.annotation.Column;
import orm.annotation.Id;
import orm.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;


@Table
@ToString
@Getter @Setter
public class Person {

    @Id
    private Long id;

    @Column(name = "firstname")
    private String firstName;

    @Column(name = "lastname")
    private String lastName;

    @Column(name = "email")
    private String email;
}
